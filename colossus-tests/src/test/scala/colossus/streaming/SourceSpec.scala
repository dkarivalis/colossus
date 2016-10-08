
package colossus
package streaming
import service.{Callback, UnmappedCallback}

import colossus.testkit._

import scala.util.{Success, Failure}

import scala.concurrent.duration._

class SourceSpec extends ColossusSpec {

  implicit val cbe = FakeIOSystem.testExecutor

  "Source" must {
    "map" in {
      val s = Source.fromIterator(Array(1, 2).toIterator)
      val t = s.map{_.toString}
      t.pull() mustBe PullResult.Item("1")
    }
  }
  
  "Source.into" must {
    def setup: (Source[Int], Pipe[Int, Int]) = {
      val x = Source.fromIterator(Array(2, 4).toIterator)
      val y = new BufferedPipe[Int](1)
      x into y
      (x, y)
    }

    "push items into sink"  in {
      val (x, y) = setup
      y.pull mustBe PullResult.Item(2)
      y.pull mustBe PullResult.Item(4)
      y.pull mustBe PullResult.Closed
    }
    "correctly deal with an initially full pipe" in {
      val x = Source.fromIterator(Array(2, 4).toIterator)
      val y = new BufferedPipe[Int](1)
      y.push(3)
      x into y
      y.pull() mustBe PullResult.Item(3)
      y.pull() mustBe PullResult.Item(2)
    }

    "terminate when downstream sink closes unexpectedly" in {
      val (x, y) = setup
      y.complete()
      x.outputState mustBe a[TransportState.Terminated]
    }

    "terminate when downstream sink is terminated" in {
      val (x, y) = setup
      y.terminate(new Exception("ADF"))
      x.outputState mustBe a[TransportState.Terminated]
    }

    "not close downstream when linkClosed is false"  in {
      val x = Source.fromIterator(Array(1).toIterator)
      val y = new BufferedPipe[Int](1)
      x.into(y, false, true)(_ => ())
      y.pull() mustBe PullResult.Item(1)
      x.outputState mustBe TransportState.Closed
      y.inputState mustBe TransportState.Open
    } 

    "not terminate downstream when linkTerminated is false" in {
      val x = Source.fromIterator(Array(1).toIterator)
      val y = new BufferedPipe[Int](1)
      x.into(y, false, false)(_ => ())
      x.terminate(new Exception("foo"))
      x.outputState mustBe a[TransportState.Terminated]
      y.inputState mustBe TransportState.Open

    }

    "notify on closing" in {
      val x = Source.empty[Int]
      val y = new BufferedPipe[Int](1)
      var notified = false
      x.into(y, false, false)(_ => {notified = true})
      x.outputState mustBe TransportState.Closed
      y.inputState mustBe TransportState.Open
      notified mustBe true
    }

    "notify on terminating" in {
      val x = new BufferedPipe[Int](1)
      val y = new BufferedPipe[Int](1)
      var notified = false
      x.into(y, false, false)(_ => {notified = true})
      x.terminate(new Exception("BYE"))
      x.outputState mustBe a[TransportState.Terminated]
      y.inputState mustBe a[TransportState.Terminated]
      notified mustBe true

    }
      
      
  }

  "Source.fromIterator" must {

    "read a full iterator" in {
      val items = Array(1, 2, 3, 4, 5, 6, 7, 8)
      val s: Source[Int] = Source.fromIterator(items.toIterator)
      s.outputState mustBe TransportState.Open
      CallbackAwait.result(s.reduce{_ + _}, 1.second) mustBe items.sum
      s.outputState mustBe TransportState.Closed
    }

    "terminate before reading everything" in {
      val s = Source.fromIterator(Array(1, 2, 3, 4).toIterator)
      CallbackAwait.result(s.pullCB, 1.second) mustBe Some(1)
      s.outputState mustBe TransportState.Open
      s.terminate(new Exception("HI"))
      s.outputState mustBe a[TransportState.Terminated]
      intercept[Exception] {
        CallbackAwait.result(s.pullCB, 1.second)
      }
    }


  }

  "Source.one" must {
    "pull" in {
      val s: Source[Int] = Source.one(5)
      CallbackAwait.result(s.pullCB, 1.second) mustBe Some(5)
      CallbackAwait.result(s.pullCB, 1.second) mustBe None
    }
    "peek" in {
      val s = Source.one(4)
      s.peek mustBe PullResult.Item(())
      s.pull() mustBe PullResult.Item(4)
      s.peek mustBe PullResult.Closed
    }
    "terminate" in {
      val s = Source.one(4)
      s.terminate(new Exception("A"))
      s.pull() mustBe a[PullResult.Error]
    }
  }


  "Source.flatten" must {
    def setup: (Source[Int], Source[Int], Sink[Source[Int]], Source[Int]) = {
      val a = Source.fromIterator(Array(1,2).toIterator)
      val b = Source.fromIterator(Array(3).toIterator)
      val pipe = new BufferedPipe[Source[Int]](5)
      val flat = Source.flatten(pipe)
      pipe.push(a)
      pipe.push(b)
      (a,b, pipe, flat)
    }

    "pull from input sources one at a time" in {
      val (a,b,_,flat) = setup
      flat.pull() mustBe PullResult.Item(1)
      flat.pull() mustBe PullResult.Item(2)
      flat.pull() mustBe PullResult.Item(3)
      flat.outputState mustBe TransportState.Open
    }

    "closing the base pipe closes the flattened pipe" taggedAs(org.scalatest.Tag("test")) in {
      val (a,b,pipe,flat) = setup
      pipe.complete()
      flat.pull() mustBe PullResult.Item(1)
      flat.pull() mustBe PullResult.Item(2)
      flat.pull() mustBe PullResult.Item(3)
      flat.outputState mustBe TransportState.Closed
    }


    "fuck up everything if flattend source terminates"  in {
      val (x,y,_,flat) = setup
      flat.terminate(new Exception("BYE"))
      x.outputState mustBe a[TransportState.Terminated]
      y.outputState mustBe a[TransportState.Terminated]
    }

    "fuck up everything if the base source terminates" ignore {
      val (x,y,pipe,_) = setup
      pipe.terminate(new Exception("BYE"))
      //TODO: the flattener is unable to terminate the sub-pipes because it
      //can't get them out of the base pipe.  We should consider allowing a
      //terminated pipe to drain before it starts reporting Error
      x.outputState mustBe a[TransportState.Terminated]
      y.outputState mustBe a[TransportState.Terminated]
    }

  }
      

  "DualSource" must {
    "correctly link two sources" in {
      val a = Source.fromIterator(Array(1, 2, 3, 4).toIterator)
      val b = Source.fromIterator(Array(5, 6, 7, 8).toIterator)

      
      val c = (a ++ b).fold((0, true)){ case (next, (last, ok)) => (next, next > last && ok) }
      CallbackAwait.result(c, 1.second) mustBe (8, true)
    }

    "terminating first source immediately terminates the dual" in {
      val x = Source.fromIterator(Array(1, 2, 3, 4).toIterator)
      val y = Source.fromIterator(Array(5, 6, 7, 8).toIterator)
      val c = (x ++ y)
      x.terminate(new Exception("A"))
      c.outputState mustBe a[TransportState.Terminated]
      intercept[Exception] {
        CallbackAwait.result(c.pullCB, 1.second)
      }
    }

    "terminating second source only terminates dual when first source is empty" in {
      val x = Source.fromIterator(Array(1).toIterator)
      val y = Source.fromIterator(Array(5, 6).toIterator)
      val c = (x ++ y)
      y.terminate(new Exception("B"))
      c.outputState mustBe TransportState.Open
      c.pull(_ => ())
      intercept[Exception] {
        CallbackAwait.result(c.pullCB, 1.second)
      }
      c.outputState mustBe a[TransportState.Terminated]

    }

    "terminating the dual terminates both sources" in {
      val x = Source.fromIterator(Array(1, 2, 3, 4).toIterator)
      val y = Source.fromIterator(Array(5, 6, 7, 8).toIterator)
      val c = (x ++ y)
      c.terminate(new Exception("C"))
      x.outputState mustBe a[TransportState.Terminated]
      y.outputState mustBe a[TransportState.Terminated]
    }
      
  }

}

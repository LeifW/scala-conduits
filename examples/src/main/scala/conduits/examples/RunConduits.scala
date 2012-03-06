package conduits
package examples

import scalaz.effect.IO
import resourcet._
import resource.{resourceTMonad, runResourceT, resourceTMonadBaseIo}

object RunConduits extends App {
  val sink = CL.sumSink[IO]
  val sinkT = CL.sumSink[({type l[a] = ResourceT[IO, a]})#l]
  val source = CL.sourceList[IO, Int]((1 to 10).toStream)
  val sourceT = CL.sourceList[({type l[a] = ResourceT[IO, a]})#l, Int]((1 to 10).toStream)


  val rt: IO[Int] = source >>== sink
  val rt2 = sourceT >>== sinkT


  println("result simple " + rt.unsafePerformIO)
  println("result resourceT " + runResourceT(rt2).unsafePerformIO)
}

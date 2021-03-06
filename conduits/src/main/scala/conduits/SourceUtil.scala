package conduits

import scalaz.{Forall, Monad}
import scalaz.effect.IO
import resourcet.{ReleaseKey, MonadResource}


sealed trait SourceStateResult[S, A] {
  def fold[Z](open: (=> S, => A) => Z, closed: => Z): Z
}

trait SourceIOResult[B] {
  def fold[Z](ioOpen: (=> B) => Z, ioClosed: => Z): Z
}

object SourceUtil {

  import Source._
  object StateOpen {
    def apply[S, A](s: => S, output: => A): SourceStateResult[S, A] = new SourceStateResult[S, A] {
      def fold[Z](open: (=> S, => A) => Z, closed: => Z) = open(s, output)
    }

    def unapply[S, A](s: => SourceStateResult[S, A]): Option[(S, A)] =
      s.fold((s, o) => Some((s, o)), None)
  }

  object StateClosed {
    def apply[S, A]: SourceStateResult[S, A] = new SourceStateResult[S, A] {
      def fold[Z](open: (=> S, => A) => Z, closed: => Z) = closed
    }

    def unapply[S, A](s: => SourceStateResult[S, A]): Boolean =
      s.fold((_,_) => false, true)
  }

  def sourceState[S, F[_], A](state: => S, pull: (S => F[SourceStateResult[S, A]]))(implicit M: Monad[F]): Source[F, A] = {
    def pull1(state: => S): F[Source[F, A]] =
      M.bind(pull(state))(res => res.fold(open = (s, o) => M.point(Open(src(s), close, o)), closed = M.point(Closed.apply)))
    def close = M.point(())
    def src(state1: => S) = SourceM(pull1(state1), close)
    src(state)
  }

  def transSource[F[_], G[_], A](f: Forall[({type λ[A] = F[A] => G[A]})#λ], source: Source[F, A])(implicit M: Monad[F], N: Monad[G]): Source[G, A] = source match {
    case Open(next, close, output) => Open(transSource(f, next), f.apply(close), output)
    case Closed() => Closed.apply[G, A]
    case SourceM(msrc, close) => SourceM[G, A](f.apply(M.map(msrc)(s => transSource(f, s))), f.apply(close))
  }

  def sourceIO[F[_], A, B, S](alloc: IO[S], cleanup: S => IO[Unit], pull: S => F[SourceIOResult[B]])(implicit M0: MonadResource[F]): Source[F, B] = {
    implicit val M = M0.MO
    def src(key: => ReleaseKey, state: => S): Source[F, B] = SourceM(pull1(key)(state), M0.release(key))
    def pull1(key: => ReleaseKey)(state: => S): F[Source[F, B]] = {
      M.bind(pull(state))((res: SourceIOResult[B]) => res.fold(
         ioOpen = b => M.point(Open.apply(src(key, state), M0.release(key), b))
         , ioClosed = M.bind(M0.release(key))(_ => M.point(Closed.apply[F, B]))))
    }
    SourceM(msrc = M.bind(M0.allocate(alloc, cleanup))(ks => pull1(ks._1)(ks._2)),
            c = M.point(()))
  }
}

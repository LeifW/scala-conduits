package conduits

import scalaz.effect._
import scalaz.std.list._
import scalaz.effect.IO._
import scalaz.Monad
import collection.immutable.{List, IntMap}

object Resources {

  def newRef[F[_], A](a: => A)(implicit R: Resource[F]): ResourceT[F, A] = {
    sys.error("todo")
  }

//  -- | The Resource transformer. This transformer keeps track of all registered
//  -- actions, and calls them upon exit (via 'runResourceT'). Actions may be
//  -- registered via 'register', or resources may be allocated atomically via
//  -- 'with' or 'withIO'. The with functions correspond closely to @bracket@.
//  --
//  -- Releasing may be performed before exit via the 'release' function. This is a
//  -- highly recommended optimization, as it will ensure that scarce resources are
//  -- freed early. Note that calling @release@ will deregister the action, so that
//  -- a release action will only ever be called once.
//  newtype ResourceT m a =
//      ResourceT (Ref (Base m) (ReleaseMap (Base m)) -> m a)

  trait ResourceT[F[_], A] {
    implicit val H: HasRef[F]
    def value: H.Ref[F[_], ReleaseMap[F[_]]] => F[A]
    def apply(istate: H.Ref[F[_], ReleaseMap[F[_]]]): F[A] = value(istate)
  }

  def register[F[_]](rel: F[Unit])(implicit R: Resource[F], H0: HasRef[F]): ResourceT[F, ReleaseKey] =
      new ResourceT[F, ReleaseKey] {
        implicit val H: HasRef[F] = H0
        def value = istate =>
            R.resourceLiftBase(registerRef(H)(istate, rel))
      }

  def registerRef[F[_]](/*implicit*/H: HasRef[F])(istate: H.Ref[F[_], ReleaseMap[F[_]]], rel: F[Unit]): F[ReleaseKey] = {
     H.atomicModifyRef(istate)((rMap: ReleaseMap[F[_]]) => {
       (ReleaseMap(rMap.key + 1, rMap.refCount, rMap.m.updated(rMap.key, rel)), ReleaseKey(rMap.key))
     })
  }

//  stateAlloc :: HasRef m => Ref m (ReleaseMap m) -> m ()
//  stateAlloc istate = do
//      atomicModifyRef' istate $ \(ReleaseMap nk rf m) ->
//          (ReleaseMap nk (rf + 1) m, ())

  def stateAlloc[F[_]](/*implicit*/H: HasRef[F])(istate: H.Ref[F[_], ReleaseMap[F[_]]]): F[Unit] =
      H.atomicModifyRef(istate)((rMap: ReleaseMap[F[_]]) =>
         (ReleaseMap(rMap.key, rMap.refCount + 1, rMap.m), ()))

  def stateCleanup[F[_], A](/*implicit*/H: HasRef[F])(istate: H.Ref[F[_], ReleaseMap[F[_]]]): F[Unit] = {
    val rmap = H.atomicModifyRef(istate)((rMap: ReleaseMap[F[_]]) =>
       (ReleaseMap(rMap.key, rMap.refCount - 1, rMap.m), (rMap.refCount - 1, rMap.m)))
    H.F.bind(rmap){case (rf, m) => {
      if (rf == Int.MinValue) {
        val l = m.values.toList
        l.foldRight(H.F.point(()))((f, b) => H.F.point(()))
//        H.F.map(H.F.sequence(m.values.toList))(x => ())
      }
      else H.F.point(())
    }}
  }
    //-- Note that there is some reference counting involved due to 'resourceForkIO'.
    //-- If multiple threads are sharing the same collection of resources, only the
    //-- last call to @runResourceT@ will deallocate the resources.
    //runResourceT :: Resource m => ResourceT m a -> m a
    //runResourceT (ResourceT r) = do
    //    istate <- resourceLiftBase $ newRef'
    //        $ ReleaseMap minBound minBound IntMap.empty
    //    resourceBracket_
    //        (stateAlloc istate)
    //        (stateCleanup istate)
    //        (r istate)

  def runResourceT[F[_], A](r: ResourceT[F, A])(implicit R: Resource[F], H: HasRef[F]): F[A] = {
    R.F.bind(R.resourceLiftBase(r.H.newRef(ReleaseMap[F[_]](Int.MinValue, Int.MinValue))))(istate => {
      R.resourceLiftBracket(stateAlloc(r.H)(istate), stateCleanup(r.H)(istate), r.apply(istate))
    })
  }

  trait Resource[F[_]] {
    implicit def F: Monad[F]
    type Base[F, A]

    def resourceLiftBase[A](base: F[A]): F[A]
    def resourceLiftBracket[A](init: F[Unit], cleanup: F[Unit], body: F[A]): F[A]
  }

  trait HasRef[F[_]] {
    implicit def F: Monad[F]
    type Ref[F, A]
    def newRef[A](a: => A): F[Ref[F[_], A]]
    def readRef[A](ref: => Ref[F[_], A]): F[A]
    def writeRef[A](a: => A)(ref: => Ref[F[_], A]): F[Unit]

    def atomicModifyRef[A, B](sa: Ref[F[_], A])(f: (A => (A, B))): F[B] = {
      F.bind(readRef(sa))(a0 => {
        val (a, b) = f(a0)
        F.bind(writeRef(a)(sa))(_ => F.point(b))
      })
    }
//    try :: m a -> m (Either SomeException a)
//    try = liftM Right
    def tryR[A](fa: F[A]): F[Either[Throwable, A]] =
      F.map(fa)(a => Right(a))
  }

  case class ReleaseKey(key: Int)

  object ReleaseMap {
    def apply[F[_]](base: F[_]): ReleaseMap[F[_]] = ReleaseMap(Int.MinValue, Int.MinValue, IntMap((0, base)))
  }

  case class ReleaseMap[A](key: Int, refCount: Int, m: Map[Int, A] = Map[Int, A]())

  trait HasRefInstances {
    implicit def ioHasRef = new HasRef[IO] {
      type Ref[F, A] = IORef[A]
      implicit def F = IO.ioMonad
      def newRef[A](a: => A) = IO.newIORef(a)

      def readRef[A](ref: => IORef[A]): IO[A] = ref.read

      def writeRef[A](a: => A)(ref: => IORef[A]) = ref.write(a)
    }

    implicit def stHasRef[S] = new HasRef[({type λ[α] = ST[S, α]})#λ] {
      implicit def F: Monad[({type λ[α] = ST[S, α]})#λ] = ST.stMonad[S]
      type Ref[F, A] = STRef[S, A]
      def newRef[A](a: => A) = ST.newVar(a)
      def readRef[A](ref: => STRef[S, A]) = ref.read
      def writeRef[A](a: => A)(ref: => STRef[S, A]) = ref.write(a).map(_ => ())
    }
  }


  object hasRefs extends HasRefInstances

  trait ResourceInstances {
    implicit def ioResource = new Resource[IO] {
      implicit def F = ioMonad
      implicit val HR: HasRef[IO] = hasRefs.ioHasRef
      type Base[F, A] = IO[A]

      type Ref[A] = IORef[A]
      //    implicit def hasRef = hasRefs.ioHasRef
      def resourceLiftBase[A](base: IO[A]) = base

      def resourceLiftBracket[A](init: IO[Unit], cleanup: IO[Unit], body: IO[A]): IO[A] =
        ExceptionControl.bracket(init)(_ => cleanup)(_ => body)
    }

    implicit def stResource[S] = new Resource[({type λ[α] = ST[S, α]})#λ] {
      val stMonad: Monad[({type λ[α] = ST[S, α]})#λ] = ST.stMonad[S] /*type annotation to keep intellij more or less happy*/
      implicit def F = stMonad
      implicit val HR: HasRef[({type λ[α] = ST[S, α]})#λ] = hasRefs.stHasRef[S]
      type Base[F, A] = ST[S, A]

      def resourceLiftBase[A](base: ST[S, A]) = base
      def resourceLiftBracket[A](ma: ST[S, Unit], mb: ST[S, Unit], mc: ST[S, A]) =
        ma.flatMap(_ => mc.flatMap(c => mb.flatMap(_ => stMonad.point(c))))
    }

  }
}

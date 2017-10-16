package tv.teads.step5

import cats.{Foldable, Id, Monad, Monoid, MonoidK, Traverse}
import cats.instances.list._
import cats.arrow.FunctionK
import cats.data.Kleisli
import tv.teads.{Ad, Country, Device}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.reflectiveCalls

object Engine {

  type ExecutionResult[T] = Either[String, T]

  type Rule[Effect[_], T] = Kleisli[Effect, T, ExecutionResult[T]]
  type SyncRule[T] = Rule[Id, T]
  type AsyncRule[T] = Rule[Future, T]

  object SyncRule {
    def apply[T](rule: T => ExecutionResult[T]): SyncRule[T] =
      Rule[Id, T](rule)
  }

  object AsyncRule {
    def apply[T](rule: T => Future[ExecutionResult[T]]): AsyncRule[T] =
      Rule[Future, T](rule)
  }

  object Rule {

    def apply[Effect[_], T](rule: T ⇒ Effect[ExecutionResult[T]]): Rule[Effect, T] =
      Kleisli[Effect, T, ExecutionResult[T]](rule)

    def monoidK[Effect[_] : Monad]: MonoidK[({type L[A] = Rule[Effect, A]})#L] = new MonoidK[({type L[A] = Rule[Effect, A]})#L] {
      val effectMonad = Monad[Effect]

      override def combineK[T](left: Rule[Effect, T], right: Rule[Effect, T]): Rule[Effect, T] = {
        Rule[Effect, T] { (t: T) =>
          effectMonad.flatMap(left(t)) {
            case Right(value) => right(value)
            case error@Left(reason) => effectMonad.pure(error)
          }
        }
      }

      override def empty[T]: Rule[Effect, T] = {
        Rule(t => effectMonad.pure(Right(t)))
      }
    }

    def combine[Effect[_] : Monad, T](left: Rule[Effect, T], right: Rule[Effect, T]): Rule[Effect, T] = {
      val combiner: Monoid[Rule[Effect, T]] = monoidK[Effect].algebra[T]

      combiner.combine(left, right)
    }

    def fold[Effect[_] : Monad, T](rules: List[Rule[Effect, T]]): Rule[Effect, T] = {
      import cats.instances.list._

      Traverse[List].fold(rules)(monoidK[Effect].algebra[T])
    }

    def transform[Effect1[_], Effect2[_] : Monad, T](left: Rule[Effect1, T], right: Rule[Effect2, T])(implicit effectTransformer: FunctionK[Effect1, Effect2]): Rule[Effect2, T] = {
      combine(left.transform(effectTransformer), right)
    }

    implicit val idToFuture: FunctionK[Id, Future] = new FunctionK[Id, Future] {
      override def apply[A](fa: Id[A]): Future[A] = Future.successful(fa)
    }

  }

  def deviceRule(device: Device): SyncRule[Ad] = {
    SyncRule(ad => Either.cond(ad.device == device, ad, "Device does not match"))
  }

  def countryRule(country: Country)(implicit ec: ExecutionContext): AsyncRule[Ad] = {
    AsyncRule(ad => Future(Either.cond(ad.country == country, ad, "Country does not match")))
  }


}

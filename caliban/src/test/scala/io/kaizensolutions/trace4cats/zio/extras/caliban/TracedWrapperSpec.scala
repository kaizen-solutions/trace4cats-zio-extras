package io.kaizensolutions.trace4cats.zio.extras.caliban

import caliban.*
import caliban.Macros.gqldoc
import caliban.schema.ArgBuilder.auto.*
import caliban.schema.Schema.auto.*
import zio.*
import zio.query.*
import zio.test.*
import io.kaizensolutions.trace4cats.zio.extras.InMemorySpanCompleter

object TracedWrapperSpec extends ZIOSpecDefault {
  val randomSleep: ZIO[Any, Nothing, Unit] = Random.nextIntBetween(0, 500).flatMap(t => ZIO.sleep(t.millis))

  final case class GetName(name: String) extends Request[Throwable, String]

  val NamesDatasource = new DataSource.Batched[Any, GetName] {
    override val identifier: String = "NamesDatasource"
    override def run(requests: Chunk[GetName])(implicit trace: zio.Trace): ZIO[Any, Nothing, CompletedRequestMap] =
      ZIO.succeed {
        CompletedRequestMap.fromIterableWith(requests)(identity, req => Exit.succeed(req.name))
      }
  }

  final case class PersonArgs(name: String)
  final case class Person(
    name: ZQuery[Any, Throwable, String],
    age: ZIO[Any, Nothing, Int],
    friends: ZIO[Any, Nothing, List[Friend]]
  )
  object Person {
    def apply(name: String): Person = new Person(
      name = ZQuery.fromZIO(randomSleep) *> ZQuery.fromRequest(GetName(name))(NamesDatasource),
      age = Random.nextIntBetween(0, 100).zip(Random.nextIntBetween(0, 500)).flatMap { case (i, d) =>
        ZIO.succeed(i).delay(d.milliseconds)
      },
      friends = randomSleep.as(List(Friend("Joe"), Friend("Bob"), Friend("Alice")))
    )
  }

  final case class Friend(
    name: ZQuery[Any, Throwable, String],
    age: ZIO[Any, Nothing, Int]
  )
  object Friend {
    def apply(name: String): Friend = new Friend(
      name = ZQuery.fromZIO(randomSleep) *> ZQuery.fromRequest(GetName(name))(NamesDatasource),
      age = Random.nextIntBetween(0, 100).zip(Random.nextIntBetween(0, 500)).flatMap { case (i, d) =>
        ZIO.succeed(i).delay(d.milliseconds)
      }
    )
  }

  final case class Queries(
    person: PersonArgs => ZIO[Any, Nothing, Person] = args => randomSleep *> ZIO.succeed(Person(args.name))
  )

  val api = graphQL(
    RootResolver(
      Queries()
    )
  ) @@ TracedWrapper.all

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("TracedWrapperSpec")(
    test("traces batched steps") {
      val query = gqldoc("""{
            person(name: "Carol") {
                name
                age
                friends {
                    name
                    age
                }
            }
        }""")

      for {
        interpreter <- api.interpreter
        _           <- interpreter.execute(query).race(TestClock.adjust(30.seconds).forever)
        spans       <- ZIO.serviceWithZIO[InMemorySpanCompleter](_.retrieveCollected)
        _           <- ZIO.debug(spans.map(_.name))
      } yield assertTrue(
        spans.map(_.name) == Chunk(
          "age",
          "age",
          "age",
          "age",
          "name",
          "name",
          "name",
          "name",
          "friends",
          "person",
          "query"
        )
      )
    }
  ).provide(InMemorySpanCompleter.layer("caliban-test"))
}

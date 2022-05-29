package io.kaizensolutions.virgil.examples

import io.kaizensolutions.virgil.codecs.CqlRowDecoder

final case class Person(id: Int, age: Int, name: String)
object Person {
  implicit val cqlRowDecoderForPerson: CqlRowDecoder.Object[Person] =
    CqlRowDecoder.derive[Person]
}

package doobie

import doobie.syntax.all.*
import io.kaizensolutions.trace4cats.zio.extras.ZTracer
import io.kaizensolutions.trace4cats.zio.extras.doobie.TracedTransactor
import zio.*
import zio.interop.catz.*

object PostgresExampleApp extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] = {
    val xaLayer = ZLayer.succeed(
      Transactor.fromDriverManager[Task](
        driver = "org.postgresql.Driver",
        url = "jdbc:postgresql:postgres",
        user = "postgres",
        password = "postgres",
        logHandler = None
      )
    )
    val tracedXaLayer: URLayer[ZTracer, Transactor[Task]] =
      ZLayer.service[ZTracer] ++ xaLayer >>> TracedTransactor.default

    val program: ZIO[Transactor[Task], Throwable, Unit] = for {
      xa     <- ZIO.service[Transactor[Task]]
      _      <- setupCity.transact(xa)
      _      <- insertCityData.transact(xa)
      startId = 1810
      endId   = 1830
      select  = sql"select id, name, countryCode, district, population from city where id > $startId and id < $endId"
      _ <- select
             .query[City]
             .stream
             .transact(xa)
             .debug()
             .compile
             .toList
      _ <- select
             .query[City]
             .to[List]
             .transact(xa)
    } yield ()

    program.provide(tracedXaLayer, JaegerEntrypoint.live, ZTracer.layer)
  }

  // https://raw.githubusercontent.com/tpolecat/doobie/series/0.7.x/world.sql
  private def setupCity: ConnectionIO[Int] =
    sql"""
          CREATE TABLE IF NOT EXISTS city (
            id integer NOT NULL,
            name varchar NOT NULL,
            countrycode character(3) NOT NULL,
            district varchar NOT NULL,
            population integer NOT NULL
          );
       """.update.run

  private val insertCityData =
    Update[City](
      """INSERT INTO city VALUES (?, ?, ?, ?, ?)"""
    ).updateMany(
      List(
        City(1810, "Montréal", "CAN", "Québec", 1016376),
        City(1810, "Montréal", "CAN", "Québec", 1016376),
        City(1811, "Calgary", "CAN", "Alberta", 768082),
        City(1812, "Toronto", "CAN", "Ontario", 688275),
        City(1813, "North York", "CAN", "Ontario", 622632),
        City(1814, "Winnipeg", "CAN", "Manitoba", 618477),
        City(1815, "Edmonton", "CAN", "Alberta", 616306),
        City(1816, "Mississauga", "CAN", "Ontario", 608072),
        City(1817, "Scarborough", "CAN", "Ontario", 594501),
        City(1818, "Vancouver", "CAN", "British Colombia", 514008),
        City(1819, "Etobicoke", "CAN", "Ontario", 348845),
        City(1820, "London", "CAN", "Ontario", 339917),
        City(1821, "Hamilton", "CAN", "Ontario", 335614),
        City(1822, "Ottawa", "CAN", "Ontario", 335277),
        City(1823, "Laval", "CAN", "Québec", 330393),
        City(1824, "Surrey", "CAN", "British Colombia", 304477),
        City(1825, "Brampton", "CAN", "Ontario", 296711),
        City(1826, "Windsor", "CAN", "Ontario", 207588),
        City(1827, "Saskatoon", "CAN", "Saskatchewan", 193647),
        City(1828, "Kitchener", "CAN", "Ontario", 189959),
        City(1829, "Markham", "CAN", "Ontario", 189098),
        City(1830, "Regina", "CAN", "Saskatchewan", 180400),
        City(1831, "Burnaby", "CAN", "British Colombia", 179209),
        City(1832, "Québec", "CAN", "Québec", 167264),
        City(1833, "York", "CAN", "Ontario", 154980),
        City(1834, "Richmond", "CAN", "British Colombia", 148867),
        City(1835, "Vaughan", "CAN", "Ontario", 147889),
        City(1836, "Burlington", "CAN", "Ontario", 145150),
        City(1837, "Oshawa", "CAN", "Ontario", 140173),
        City(1838, "Oakville", "CAN", "Ontario", 139192),
        City(1839, "Saint Catharines", "CAN", "Ontario", 136216),
        City(1840, "Longueuil", "CAN", "Québec", 127977),
        City(1841, "Richmond Hill", "CAN", "Ontario", 116428),
        City(1842, "Thunder Bay", "CAN", "Ontario", 115913),
        City(1843, "Nepean", "CAN", "Ontario", 115100),
        City(1844, "Cape Breton", "CAN", "Nova Scotia", 114733),
        City(1845, "East York", "CAN", "Ontario", 114034),
        City(1846, "Halifax", "CAN", "Nova Scotia", 113910),
        City(1847, "Cambridge", "CAN", "Ontario", 109186),
        City(1848, "Gloucester", "CAN", "Ontario", 107314),
        City(1849, "Abbotsford", "CAN", "British Colombia", 105403),
        City(1850, "Guelph", "CAN", "Ontario", 103593),
        City(1851, "Saint John´s", "CAN", "Newfoundland", 101936),
        City(1852, "Coquitlam", "CAN", "British Colombia", 101820),
        City(1853, "Saanich", "CAN", "British Colombia", 101388),
        City(1854, "Gatineau", "CAN", "Québec", 100702),
        City(1855, "Delta", "CAN", "British Colombia", 95411),
        City(1856, "Sudbury", "CAN", "Ontario", 92686),
        City(1857, "Kelowna", "CAN", "British Colombia", 89442),
        City(1858, "Barrie", "CAN", "Ontario", 89269)
      )
    )

}

final case class City(id: Int, name: String, countryCode: String, district: String, population: Int)

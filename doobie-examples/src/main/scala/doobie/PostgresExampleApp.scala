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
        pass = "postgres"
      )
    )
    val tracedXaLayer: URLayer[ZTracer, Transactor[Task]] =
      ZLayer.service[ZTracer] ++ xaLayer >>> TracedTransactor.layer

    val program: ZIO[Transactor[Task], Throwable, Unit] = for {
      xa     <- ZIO.service[Transactor[Task]]
      _      <- setupCity.transact(xa)
      _      <- insertCityData.transact(xa)
      startId = 1810
      endId   = 1830
      _ <- sql"select id, name, countryCode, district, population from city where id > $startId and id < $endId"
             .query[City]
             .stream
             .transact(xa)
             .debug()
             .compile
             .toList
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
    sql"""
    INSERT INTO city VALUES (1810, 'Montréal', 'CAN', 'Québec', 1016376);
    INSERT INTO city VALUES (1811, 'Calgary', 'CAN', 'Alberta', 768082);
    INSERT INTO city VALUES (1812, 'Toronto', 'CAN', 'Ontario', 688275);
    INSERT INTO city VALUES (1813, 'North York', 'CAN', 'Ontario', 622632);
    INSERT INTO city VALUES (1814, 'Winnipeg', 'CAN', 'Manitoba', 618477);
    INSERT INTO city VALUES (1815, 'Edmonton', 'CAN', 'Alberta', 616306);
    INSERT INTO city VALUES (1816, 'Mississauga', 'CAN', 'Ontario', 608072);
    INSERT INTO city VALUES (1817, 'Scarborough', 'CAN', 'Ontario', 594501);
    INSERT INTO city VALUES (1818, 'Vancouver', 'CAN', 'British Colombia', 514008);
    INSERT INTO city VALUES (1819, 'Etobicoke', 'CAN', 'Ontario', 348845);
    INSERT INTO city VALUES (1820, 'London', 'CAN', 'Ontario', 339917);
    INSERT INTO city VALUES (1821, 'Hamilton', 'CAN', 'Ontario', 335614);
    INSERT INTO city VALUES (1822, 'Ottawa', 'CAN', 'Ontario', 335277);
    INSERT INTO city VALUES (1823, 'Laval', 'CAN', 'Québec', 330393);
    INSERT INTO city VALUES (1824, 'Surrey', 'CAN', 'British Colombia', 304477);
    INSERT INTO city VALUES (1825, 'Brampton', 'CAN', 'Ontario', 296711);
    INSERT INTO city VALUES (1826, 'Windsor', 'CAN', 'Ontario', 207588);
    INSERT INTO city VALUES (1827, 'Saskatoon', 'CAN', 'Saskatchewan', 193647);
    INSERT INTO city VALUES (1828, 'Kitchener', 'CAN', 'Ontario', 189959);
    INSERT INTO city VALUES (1829, 'Markham', 'CAN', 'Ontario', 189098);
    INSERT INTO city VALUES (1830, 'Regina', 'CAN', 'Saskatchewan', 180400);
    INSERT INTO city VALUES (1831, 'Burnaby', 'CAN', 'British Colombia', 179209);
    INSERT INTO city VALUES (1832, 'Québec', 'CAN', 'Québec', 167264);
    INSERT INTO city VALUES (1833, 'York', 'CAN', 'Ontario', 154980);
    INSERT INTO city VALUES (1834, 'Richmond', 'CAN', 'British Colombia', 148867);
    INSERT INTO city VALUES (1835, 'Vaughan', 'CAN', 'Ontario', 147889);
    INSERT INTO city VALUES (1836, 'Burlington', 'CAN', 'Ontario', 145150);
    INSERT INTO city VALUES (1837, 'Oshawa', 'CAN', 'Ontario', 140173);
    INSERT INTO city VALUES (1838, 'Oakville', 'CAN', 'Ontario', 139192);
    INSERT INTO city VALUES (1839, 'Saint Catharines', 'CAN', 'Ontario', 136216);
    INSERT INTO city VALUES (1840, 'Longueuil', 'CAN', 'Québec', 127977);
    INSERT INTO city VALUES (1841, 'Richmond Hill', 'CAN', 'Ontario', 116428);
    INSERT INTO city VALUES (1842, 'Thunder Bay', 'CAN', 'Ontario', 115913);
    INSERT INTO city VALUES (1843, 'Nepean', 'CAN', 'Ontario', 115100);
    INSERT INTO city VALUES (1844, 'Cape Breton', 'CAN', 'Nova Scotia', 114733);
    INSERT INTO city VALUES (1845, 'East York', 'CAN', 'Ontario', 114034);
    INSERT INTO city VALUES (1846, 'Halifax', 'CAN', 'Nova Scotia', 113910);
    INSERT INTO city VALUES (1847, 'Cambridge', 'CAN', 'Ontario', 109186);
    INSERT INTO city VALUES (1848, 'Gloucester', 'CAN', 'Ontario', 107314);
    INSERT INTO city VALUES (1849, 'Abbotsford', 'CAN', 'British Colombia', 105403);
    INSERT INTO city VALUES (1850, 'Guelph', 'CAN', 'Ontario', 103593);
    INSERT INTO city VALUES (1851, 'Saint John´s', 'CAN', 'Newfoundland', 101936);
    INSERT INTO city VALUES (1852, 'Coquitlam', 'CAN', 'British Colombia', 101820);
    INSERT INTO city VALUES (1853, 'Saanich', 'CAN', 'British Colombia', 101388);
    INSERT INTO city VALUES (1854, 'Gatineau', 'CAN', 'Québec', 100702);
    INSERT INTO city VALUES (1855, 'Delta', 'CAN', 'British Colombia', 95411);
    INSERT INTO city VALUES (1856, 'Sudbury', 'CAN', 'Ontario', 92686);
    INSERT INTO city VALUES (1857, 'Kelowna', 'CAN', 'British Colombia', 89442);
    INSERT INTO city VALUES (1858, 'Barrie', 'CAN', 'Ontario', 89269);""".update.run

}

final case class City(id: Int, name: String, countryCode: String, district: String, population: Int)

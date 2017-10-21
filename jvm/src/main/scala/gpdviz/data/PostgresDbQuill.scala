package gpdviz.data

import com.typesafe.config.Config
import gpdviz.model._
import gpdviz.server.{GnError, ObservationsRegister, SSUpdate}
import io.getquill.{Embedded, LowerCase, PostgresJdbcContext}
import pprint.PPrinter.Color.{apply ⇒ pp}
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class PgQLatLon(lat: Double, lon: Double) extends Embedded

case class PgQSensorSystem(
                          sysid:        String,
                          name:         Option[String] = None,
                          description:  Option[String] = None,
                          pushEvents:   Boolean = true,
                          center:       Option[PgQLatLon] = None,
                          zoom:         Option[Int] = None,
                          clickListener: Option[String] = None
                         )

case class PgQDataStream(
                        sysid:        String,
                        strid:        String,
                        name:         Option[String] = None,
                        description:  Option[String] = None,
                        mapStyle:     Option[String] = None,
                        zOrder:       Int = 0,
                        chartStyle:   Option[String] = None
                       )

case class PgQVariableDef(
                          sysid:         String,
                          strid:         String,
                          name:          String,
                          units:         Option[String] = None,
                          chartStyle:    Option[String] = None
                        )

case class PgQScalarData(
                         vars:      List[String],
                         vals:      List[Double],
                       // TODO quill#932
                       //position:  Option[PgQLatLon] = None
                         lat:       Option[Double],
                         lon:       Option[Double]
                       ) extends Embedded

case class PgQObservation(
                          sysid:         String,
                          strid:         String,
                          time:          String,
                          feature:       Option[String] = None,
                          geometry:      Option[String] = None,
                          scalarData:    Option[PgQScalarData] = None
                        )

class PostgresDbQuill(quillConfig: Config) extends DbInterface {

  private val ctx = new PostgresJdbcContext(LowerCase, quillConfig)
  import ctx._

  private val sensorSystem = quote {
    querySchema[PgQSensorSystem]("sensorsystem",
      _.center.map(_.lat) → "centerLat",
      _.center.map(_.lon) → "centerLon"
    )
  }

  private val dataStream = quote {
    querySchema[PgQDataStream]("datastream"
    )
  }

  private val variableDef = quote {
    querySchema[PgQVariableDef]("variabledef"
    )
  }

  private val observation = quote {
    querySchema[PgQObservation]("observation"
      // TODO quill#932
      //,
      //_.scalarData.map(_.vars) → "scalarDataVars",
      //_.scalarData.map(_.vals) → "scalarDataVals",
      //_.scalarData.map(_.position.map(_.lat)) → "scalarDataLat",
      //_.scalarData.map(_.position.map(_.lon)) → "scalarDataLon",
    )
  }

  val details: String = s"PostgreSQL-based database"

  def createTables(): Unit = ()

  def listSensorSystems(): Future[Seq[SensorSystemSummary]] = Future {
    ctx.run(sensorSystem) map { pss ⇒
      val strIds = ctx.run(dataStream.filter(_.sysid == lift(pss).sysid)).map(_.strid)
      SensorSystemSummary(
        pss.sysid,
        pss.name,
        pss.description,
        strIds.toSet
      )
    }
  }

  def existsSensorSystem(sysid: String): Future[Boolean] = Future {
    ctx.run(sensorSystem.filter(_.sysid == lift(sysid))).nonEmpty
  }

  def registerSensorSystem(ss: SensorSystem): Future[Either[GnError, SensorSystemSummary]] = Future {
    ctx.run(sensorSystem.insert(lift(PgQSensorSystem(
      sysid = ss.sysid,
      name = ss.name,
      pushEvents = ss.pushEvents,
      center = ss.center.map(c ⇒ PgQLatLon(c.lat, c.lon)),
      clickListener = ss.clickListener
    ))))

    val regStream = registerDataStream(ss.sysid) _
    ss.streams.values foreach regStream

    Right(SensorSystemSummary(
      ss.sysid,
      name = ss.name,
      description = ss.description
    ))
  }

  // TODO seems like quill's update operation is also affected by the nested Embedding issue.
  // The test is currently ignored in GpdvizSpec
  def updateSensorSystem(sysid: String, ssu: SSUpdate): Future[Either[GnError, SensorSystemSummary]] = Future {
    ctx.run(sensorSystem.filter(_.sysid == lift(sysid)).update { ss ⇒
      ss.pushEvents → lift(ssu.pushEvents getOrElse ss.pushEvents)
      ss.center → lift(ssu.center orElse ss.center)
      // TODO 'refresh'?
    })
    Right(SensorSystemSummary(sysid))
  }

  def registerDataStream(sysid: String)
                        (ds: DataStream): Future[Either[GnError, DataStreamSummary]] = Future {
    ctx.run(dataStream.insert(lift(PgQDataStream(
      sysid = sysid,
      strid = ds.strid,
      name = ds.name,
      description = ds.description,
      mapStyle = ds.mapStyle.map(utl.toJsonString),
      zOrder = ds.zOrder,
      chartStyle = ds.chartStyle.map(utl.toJsonString)
    ))))

    val variables = ds.variables.getOrElse(List.empty)
    val observations = ds.observations.getOrElse(Map.empty)

    println(s"  ** sysid=$sysid strid=${ds.strid} Registering ${variables.size} variables: $variables")
    variables foreach registerVariableDef(sysid, ds.strid)

    println(s"  ** sysid=$sysid strid=${ds.strid} Registering ${observations.size} observations")
    observations foreach { case (time, list) ⇒
      //println(s"  **- time=$time list.size=${list.size}")
      //val timeIso = if (time.startsWith("15030")) utl.iso(time.toLong) else time
      list.foreach(registerObservation(sysid, ds.strid, time, _))
    }

    Right(DataStreamSummary(sysid, ds.strid))
  }

  def registerVariableDef(sysid: String, strid: String)
                         (vd: VariableDef): Future[Either[GnError, VariableDefSummary]] = Future {
    println(s"  *** registerVariableDef ${vd.name}")
    ctx.run(variableDef.insert(lift(PgQVariableDef(
      sysid = sysid,
      strid = strid,
      name = vd.name,
      units = vd.units
    ))))
    Right(VariableDefSummary(sysid, strid, vd.name, vd.units))
  }

  def registerObservations(sysid: String, strid: String)
                          (obssr: ObservationsRegister): Future[Either[GnError, ObservationsSummary]] = Future {

    var num = 0
    obssr.observations foreach { case (time, list) ⇒
      //val timeIso = if (time.startsWith("15030")) utl.iso(time.toLong) else time
      if (sysid=="ss1" && strid=="str1")
        println(s"  **- time=$time list=${pp(list)}")
      list.foreach(registerObservation(sysid, strid, time, _))
      num += list.length
    }
    Right(ObservationsSummary(sysid, strid, added = Some(num)))
  }

  def registerObservation(sysid: String, strid: String, time: String,
                          obsData: ObsData): Future[Either[GnError, ObservationsSummary]] = Future {

    val feature = obsData.feature.map(utl.toJsonString)
    val geometry = obsData.geometry.map(utl.toJsonString)

    val pgScalarData = obsData.scalarData map { s ⇒
      PgQScalarData(
        vars = s.vars,
        vals = s.vals,
      //position = s.position.map(p ⇒ PgQLatLon(p.lat, p.lon))
        lat = s.position.map(_.lat),
        lon = s.position.map(_.lon)
      )
    }
    //pgScalarData foreach { x ⇒ println(s"  *** registerObservation sysid=$sysid strid=$strid time=$time pgScalarData=" + pp(x)) }

    val pgObservation = PgQObservation(
      sysid = sysid,
      strid = strid,
      time = time,
      feature = feature,
      geometry = geometry,
      scalarData = pgScalarData
    )
    println(s"  *** registerObservation sysid=$sysid strid=$strid pgObservation=" + pp(pgObservation))
    ctx.run(observation.insert(lift(pgObservation)))
    Right(ObservationsSummary(sysid, strid, time = Some(time), added = Some(1)))
  }

  private def getPgObservations(sysid: String, strid: String): List[PgQObservation] = {
    ctx.run(observation
      .filter(o ⇒ o.sysid == lift(sysid) && o.strid == lift(strid))
    )
  }

  def getSensorSystem(sysid: String): Future[Option[SensorSystem]] = Future {
    val r = ctx.run(sensorSystem.filter(_.sysid == lift(sysid)))
    r.headOption.map { pss ⇒
      val streams = ctx.run(dataStream.filter(_.sysid == lift(pss).sysid)).map { ds ⇒

        val varDefs: List[VariableDef] = {
          ctx.run(variableDef.filter(vd ⇒ vd.sysid == lift(ds).sysid && vd.strid == lift(ds).strid)).map { vd ⇒
            VariableDef(
              name = vd.name,
              units = vd.units,
              chartStyle = vd.chartStyle.map(x ⇒ JsonParser(x).asJsObject)
            )
          }
        }

        val observationsMap: Map[String, List[ObsData]] = {

          def pgObs2ObsData(o: PgQObservation): ObsData = {
            ObsData(
              feature = o.feature.map(utl.toFeature),
              geometry = o.geometry.map(utl.toGeometry),
              scalarData = o.scalarData.map { sd ⇒
                ScalarData(
                  vars = sd.vars,
                  vals = sd.vals,
                //position = sd.position.map(p ⇒ LatLon(p.lat, p.lon))
                  position = for {
                    lat ← sd.lat
                    lon ← sd.lon
                  } yield LatLon(lat, lon)
                )
              }
            )
          }

          // how's quill's groupBy actually work?

          val observations = getPgObservations(ds.sysid, ds.strid)

          observations.groupBy(_.time).mapValues(_ map pgObs2ObsData)
        }

        DataStream(
          ds.strid,
          ds.name,
          ds.description,
          mapStyle = ds.mapStyle.map(x ⇒ JsonParser(x).asJsObject),
          zOrder = ds.zOrder,
          chartStyle = ds.chartStyle.map(x ⇒ JsonParser(x).asJsObject),
          variables = if (varDefs.nonEmpty) Some(varDefs) else None,
          observations = Some(observationsMap)
        )
      }

      SensorSystem(
        pss.sysid,
        pss.name,
        pss.description,
        pushEvents = pss.pushEvents,
        center = pss.center.map(c ⇒ LatLon(c.lat, c.lon)),
        zoom = pss.zoom,
        clickListener = pss.clickListener,
        streams = streams.map(s ⇒ (s.strid, s)).toMap
      )
    }
  }

  def deleteSensorSystem(sysid: String): Future[Either[GnError, SensorSystemSummary]] = Future {
    ctx.transaction {
      ctx.run(observation.filter(_.sysid  == lift(sysid)).delete)
      ctx.run(variableDef.filter(_.sysid  == lift(sysid)).delete)
      ctx.run(dataStream.filter(_.sysid   == lift(sysid)).delete)
      ctx.run(sensorSystem.filter(_.sysid == lift(sysid)).delete)
    }
    Right(SensorSystemSummary(sysid))
  }

  def deleteDataStream(sysid: String, strid: String): Future[Either[GnError, DataStreamSummary]] = Future {
    ctx.transaction {
      ctx.run(observation.filter(x ⇒ x.sysid == lift(sysid) && x.strid == lift(strid)).delete)
      ctx.run(variableDef.filter(x ⇒ x.sysid == lift(sysid) && x.strid == lift(strid)).delete)
      ctx.run(dataStream.filter( x ⇒ x.sysid == lift(sysid) && x.strid == lift(strid)).delete)
    }
    Right(DataStreamSummary(sysid, strid))
  }

  def close(): Unit = ctx.close()
}

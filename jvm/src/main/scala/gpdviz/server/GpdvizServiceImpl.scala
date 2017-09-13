package gpdviz.server

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.{Conflict, InternalServerError, NotFound}
import akka.http.scaladsl.model._
import gpdviz.async.Notifier
import gpdviz.data.DbInterface
import gpdviz.model.{DataStream, SensorSystem}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

trait GpdvizServiceImpl extends JsonImplicits  {
  def db: DbInterface
  def notifier: Notifier

  def registerSensorSystem(ssr: SSRegister): Future[ToResponseMarshallable] = {
    val p = Promise[ToResponseMarshallable]()
    db.getSensorSystem(ssr.sysid) map {
      case None ⇒
        val ss = SensorSystem(ssr.sysid,
          name = ssr.name,
          description = ssr.description,
          pushEvents = ssr.pushEvents.getOrElse(true),
          center = ssr.center,
          clickListener = ssr.clickListener
        )
        db.saveSensorSystem(ss) map {
          case Right(rss) ⇒
            notifier.notifySensorSystemRegistered(rss)
            p.success(rss)
          case Left(error) ⇒
            p.success(InternalServerError -> error)
        }

      case Some(_) ⇒
        p.success(Conflict -> GnError(409, "Already registered", sysid = Some(ssr.sysid)))
    }

    p.future
  }

  def getSensorSystem(sysid: String): Future[ToResponseMarshallable] = withSensorSystem(sysid) { ss ⇒ Future(ss) }

  def updateSensorSystem(sysid: String, ssu: SSUpdate): Future[ToResponseMarshallable] = withSensorSystem(sysid) { ss ⇒
    println(s"updateSensorSystem: sysid=$sysid ssu=$ssu")

    var updated = ss.copy()
    ssu.pushEvents foreach {
      pe ⇒ updated = updated.copy(pushEvents = pe)
    }
    ssu.center foreach { _ ⇒
      updated = updated.copy(center = ssu.center)
    }

    db.saveSensorSystem(updated) map {
      case Right(uss) ⇒
        notifier.notifySensorSystemUpdated(uss)
        if (ssu.refresh.getOrElse(false)) {
          notifier.notifySensorSystemRefresh(uss)
        }
        uss
      case Left(error) ⇒ InternalServerError -> error
    }
  }

  def unregisterSensorSystem(sysid: String): Future[ToResponseMarshallable] = withSensorSystem(sysid) { ss ⇒
    println(s"unregisterSensorSystem: sysid=$sysid")
    db.deleteSensorSystem(sysid) map {
      case Right(s) ⇒
        notifier.notifySensorSystemUnregistered(s)
        s
      case Left(error) ⇒ InternalServerError -> error
    }
  }

  def addStream(sysid: String, strr: StreamRegister): Future[ToResponseMarshallable] = withSensorSystem(sysid) { ss ⇒
    println(s"addStream: sysid=$sysid strid=${strr.strid}")
    ss.streams.get(strr.strid) match {
      case None ⇒
        val ds = DataStream(
          strid       = strr.strid,
          name        = strr.name,
          description = strr.description,
          mapStyle    = strr.mapStyle,
          zOrder      = strr.zOrder.getOrElse(0),
          variables   = strr.variables,
          chartStyle  = strr.chartStyle
        )
        val updated = ss.copy(streams = ss.streams.updated(strr.strid, ds))
        db.saveSensorSystem(updated) map {
          case Right(uss) ⇒
            notifier.notifyStreamAdded(uss, ds)
            uss
          case Left(error) ⇒ InternalServerError -> error
        }

      case Some(_) ⇒ Future(streamAlreadyDefined(sysid, strr.strid))
    }
  }

  def addObservations(sysid: String, strid: String, obssr: ObservationsRegister): Future[ToResponseMarshallable] = withSensorSystem(sysid) { ss ⇒
    println(s"addObservations: sysid=$sysid, strid=$strid, obssr=${obssr.observations.size}")
    ss.streams.get(strid) match {
      case Some(str) ⇒
        val newObs = obssr.observations
        val previous = str.observations.getOrElse(Map.empty)
        var obsUpdated = previous
        newObs foreach { case (k, v) ⇒
          //v.map(_.geometry).filter(_.isDefined).map(_.get) foreach {geometry ⇒
          //  println(s"::: geometry: type=${geometry.getType}: $geometry")
          //}
          obsUpdated = obsUpdated.updated(k, v)
        }
        val strUpdated = str.copy(observations = Some(obsUpdated))
        val ssUpdated = ss.copy(streams = ss.streams.updated(strid, strUpdated))
        db.saveSensorSystem(ssUpdated) map {
          case Right(uss) ⇒
            notifier.notifyObservations2Added(uss, strid, newObs)
            newObs

          case Left(error) ⇒ InternalServerError -> error
        }

      case None ⇒ Future(streamUndefined(sysid, strid))
    }
  }

  def getStream(sysid: String, strid: String): Future[ToResponseMarshallable] = withSensorSystem(sysid) { ss ⇒
    Future {
      ss.streams.get(strid) match {
        case Some(str) ⇒ str
        case None ⇒ streamUndefined(sysid, strid)
      }
    }
  }

  def deleteStream(sysid: String, strid: String): ToResponseMarshallable = withSensorSystem(sysid) { ss ⇒
    println(s"deleteStream: sysid=$sysid strid=$strid")
    ss.streams.get(strid) match {
      case Some(_) ⇒
        val updated = ss.copy(streams = ss.streams - strid)
        db.saveSensorSystem(updated) map {
          case Right(uss) ⇒
            notifier.notifyStreamRemoved(uss, strid)
            uss
          case Left(error) ⇒ InternalServerError -> error
        }

      case None ⇒ Future(streamUndefined(sysid, strid))
    }
  }

  private def withSensorSystem(sysid: String)(p : SensorSystem ⇒ Future[ToResponseMarshallable]): Future[ToResponseMarshallable] = {
    db.getSensorSystem(sysid) map {
      case Some(ss) ⇒ p(ss)
      case None ⇒ NotFound -> GnError(404, "not registered", sysid = Some(sysid))
    }
  }

  def getSensorSystemIndex(sysid: String): Future[ToResponseMarshallable] = {
    db.getSensorSystem(sysid) map { ssOpt ⇒
      val ssIndex = notifier.getSensorSystemIndex(sysid, ssOpt)
      HttpEntity(ContentType(MediaTypes.`text/html`, HttpCharsets.`UTF-8`), ssIndex.getBytes("UTF-8"))
    }
  }

  private def streamUndefined(sysid: String, strid: String): (StatusCodes.ClientError, GnError) =
    NotFound -> GnError(404, "stream undefined", sysid = Some(sysid), strid = Some(strid))

  def streamAlreadyDefined(sysid: String, strid: String): (StatusCodes.ClientError, GnError) =
    Conflict -> GnError(409, "stream already defined", sysid = Some(sysid), strid = Some(strid))
}

package gpdviz.webapp

import com.thoughtworks.binding.Binding.{Var, Vars}
import gpdviz.ClientConfig
import gpdviz.model.{LatLon, VmDataStream, VmObsData, VmSensorSystem}
import upickle.Js

import scala.scalajs.js
import scala.scalajs.js.JSConverters._


class VModel(sysid: String, cc: ClientConfig, llmap: LLMap) {

  val ss: Var[VmSensorSystem] = Var(VmSensorSystem(sysid))

  val absoluteCharts: Vars[ChartDiv] = Vars.empty

  def refreshSystem(vss: VmSensorSystem): Unit = {
    ss := vss

    llmap.setView(jsCenter(vss.center), vss.zoom.getOrElse(cc.zoom))

    vss.streams foreach { str ⇒
      addAbsoluteChartIfSo(str.strid, str.chartStyle)
      addStreamToMap(str)
      str.observations foreach { obss ⇒
        addObservationsToMap(str, obss)
      }
    }
  }

  def addSensorSystem(name:          Option[String],
                      description:   Option[String],
                      center:        Option[LatLon],
                      zoom:          Option[Int],
                      clickListener: Option[String]): Unit = {
    ss := VmSensorSystem(
      sysid = sysid,
      name = name,
      description = description,
      streams = List.empty,
      center = center,
      clickListener = clickListener
    )
    llmap.sensorSystemAdded(jsCenter(center), zoom.getOrElse(cc.zoom))
  }

  private def jsCenter(center: Option[LatLon]): js.Array[Double] = {
    val c = center.getOrElse(cc.center)
    js.Array(c.lat, c.lon)
  }

  def deleteSensorSystem(): Unit = {
    ss := VmSensorSystem(sysid)
    llmap.sensorSystemDeleted()
  }

  def addDataStream(str: VmDataStream): Unit = {
    ss := ss.get.copy(streams = str :: ss.get.streams)
    addAbsoluteChartIfSo(str.strid, str.chartStyle)
    addStreamToMap(str)
  }

  private def addAbsoluteChartIfSo(strid: String, chartStyle: Option[String]): Unit = {
    val jsObj: Js.Obj = chartStyle.map(upickle.default.read[Js.Obj]).getOrElse(Js.Obj())
    //println(s"strid=$strid  jsObj=$jsObj")

    val useChartPopup = jsObj.obj.get("useChartPopup").contains(Js.True)
    if (!useChartPopup) {
      val chartId = "chart-container-" + strid
      val chartHeightStr: String = jsObj.obj.get("height") match {
        case Some(Js.Str(value)) ⇒ value
        case Some(Js.Num(value)) ⇒ value.toInt + "px"
        case _                   ⇒ 370 + "px"
      }
      val minWidthStr: String = jsObj.obj.get("minWidth") match {
        case Some(Js.Str(value)) ⇒ value
        case Some(Js.Num(value)) ⇒ value.toInt + "px"
        case _                   ⇒ 500 + "px"
      }
      //println(s"strid=$strid  chartHeightStr=$chartHeightStr  minWidthStr=$minWidthStr")
      absoluteCharts.get += ChartDiv(chartId, chartHeightStr, minWidthStr)
    }
  }

  private def addStreamToMap(str: VmDataStream): Unit = {
    llmap.addDataStream(Map(
      "strid"        → str.strid,
      "name"         → str.name.orUndefined,
      "description"  → str.description.orUndefined,
      "mapStyle"     → str.mapStyle.orUndefined,
      "zOrder"       → str.zOrder,

      "chartStyle"   → str.chartStyle.orUndefined,
      "variables"    → str.variables.map(vars ⇒ vars.map(v ⇒ Map(
        "name"       → v.name,
        "units"      → v.units.orUndefined,
        "chartStyle" → v.chartStyle.orUndefined
      ).toJSDictionary).toJSArray).orUndefined

      //,"observations" → str.observations.toJSArray
    ).toJSDictionary)
  }

  def deleteDataStream(strid: String): Unit = {
    val streams = ss.get.streams.filterNot(_.strid == strid)
    ss := ss.get.copy(streams = streams)
    llmap.deleteDataStream(strid)
  }

  def addObservations(strid: String,
                      obss: Map[String, List[VmObsData]]): Unit = {
    val str = ss.get.streams.find(_.strid == strid).getOrElse(throw new IllegalStateException(s"undefined stream $strid"))
    val currObss = str.observations getOrElse Map.empty
    val newObss = currObss ++ obss
    val updatedStr = str.copy(observations = Some(newObss))
    ss := ss.get.copy(streams = updatedStr :: ss.get.streams.filterNot(_.strid == strid))

    addObservationsToMap(str, obss)
  }

  private def addObservationsToMap(str: VmDataStream,
                                   obss: Map[String, List[VmObsData]]): Unit = {
    obss.keys.toSeq.sorted foreach { timestamp ⇒
      obss(timestamp) foreach { obs ⇒
        obs.feature foreach { feature ⇒
          llmap.addGeoJson(str.strid, timestamp, feature)
        }
        obs.geometry foreach { geometry ⇒
          llmap.addGeoJson(str.strid, timestamp, geometry)
        }
        obs.scalarData foreach { scalarData ⇒
          llmap.addObsScalarData(str.strid, timestamp, Map(
            "vars" → scalarData.vars.toJSArray,
            "vals" → scalarData.vals.toJSArray,
            "position" → scalarData.position.map(p ⇒
              Map("lat" → p.lat, "lon" → p.lon).toJSDictionary).orUndefined
          ).toJSDictionary)

          scalarData.position foreach { position ⇒
            val timeMs = timestamp.toLong
            PositionsByTime.set(str.strid, timeMs, position)
          }
        }
      }
    }
  }
}

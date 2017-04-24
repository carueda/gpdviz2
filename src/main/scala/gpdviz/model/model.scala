package gpdviz.model

import com.cloudera.science.geojson.Feature
import com.esri.core.geometry.Geometry
import spray.json.JsValue

case class LatLon(lat: Double, lon: Double)

case class SensorSystem(sysid:        String,
                        name:         Option[String] = None,
                        description:  Option[String] = None,
                        streams:      Map[String, DataStream] = Map.empty,
                        pushEvents:   Boolean = true,
                        center:       Option[LatLon] = None
                        )

case class DataStream(strid:    String,
                      style:    Option[Map[String, JsValue]] = None,
                      zOrder:   Int = 0,
                      variables: Option[List[String]] = None,
                      obs:      Option[List[DataObs]] = None
                      )

case class TimestampedData(timestamp: Long, values: List[Double])

case class DataObs(timestamp:   Long,
                   feature:     Option[Feature] = None,
                   geometry:    Option[Geometry] = None,
                   chartTsData: Option[List[TimestampedData]] = None
                   )

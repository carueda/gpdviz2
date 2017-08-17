package gpdviz.webapp

import com.thoughtworks.binding.Binding.Constants
import com.thoughtworks.binding.{Binding, dom}
import gpdviz.model.VmDataStream
import org.scalajs.dom.raw.Node
import pprint.PPrinter.BlackWhite.{apply ⇒ pp}

case class AbsChart(
                   id:          String,
                   heightStr:   String,
                   minWidthStr: String
                   )

class View(vm: VModel) {

  def render(): Unit = {

    dom.render(elm.sysName, sysName)
    dom.render(elm.sysDesc, sysDesc)
    dom.render(elm.absoluteCharts, absoluteCharts)
    dom.render(elm.sysActivity, sysActivity())
  }

  @dom private val sysName = <span>{ vm.ss.bind.name.map(" - " + _).getOrElse("") }</span>

  @dom private val sysDesc = <span>{ vm.ss.bind.description.getOrElse("") }</span>

  @dom private val absoluteCharts = <div>
    {
      vm.absCharts map { c ⇒
        //println("ADDING absoluteChart div id=" + c.id)
        <div id={ c.id }
             class="absoluteChart"
             style={ s"'min-width': ${c.minWidthStr}, height: ${c.heightStr}" }
        >
        </div>
      }
    }
  </div>

  @dom private def sysActivity(): Binding[Node] = {
    <div>
      <div>Center: { vm.ss.bind.center.map(_.toString).getOrElse("") }</div>
      <div>{ streamsBinding.bind }</div>
    </div>
  }

  @dom private def streamsBinding: Binding[Node] = {
    <ul>
      {
      for (str <- Constants(vm.ss.bind.streams.values.toSeq: _*))
        yield <li>{ streamBinding(str).bind } </li>
      }
    </ul>
  }

  @dom private def streamBinding(str: VmDataStream): Binding[Node] = {
    <div>
      { str.strid + str.description.map(" - " + _).getOrElse("") }
      <div>Variables:
        <ul>
          {
          for (v <- Constants(str.variables.getOrElse(List.empty): _*))
            yield <li>{ v.name + v.units.map( " units=" + _).getOrElse("") }</li>
          }
        </ul>
      </div>
      <div>Observations:
        <ul>
          {
          val obss = str.observations.getOrElse(Map.empty)
          for (timestamp <- Constants(obss.keys.toSeq.sorted: _*))
            yield <li>{ timestamp + " -> " + obss(timestamp).map(pp(_)) }</li>
          }
        </ul>
      </div>
    </div>
  }
}

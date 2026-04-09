package app.aaps.plugins.aps.carbmodel

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import app.aaps.core.graph.data.GraphViewWithCleanup
import app.aaps.plugins.aps.openAPSAIMI.pkpd.CarbAbsorptionModel
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import java.text.NumberFormat

class CarbModelGraph @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : GraphViewWithCleanup(context, attrs, defStyle) {

    fun show() {
        removeAllSeries()
        mSecondScale = null
        val foodTypes = CarbAbsorptionModel.FoodType.entries
        val colors = intArrayOf(
            Color.rgb(244, 143, 32),
            Color.rgb(33, 150, 243),
            Color.rgb(216, 67, 21)
        )

        foodTypes.forEachIndexed { index, foodType ->
            val weights = CarbAbsorptionModel.buildWeights(steps = 48, foodType = foodType)
            val data = Array(weights.size) { i -> DataPoint(((i + 1) * 5).toDouble(), weights[i] * 100.0) }
            addSeries(LineGraphSeries(data).also { series ->
                series.color = colors[index % colors.size]
                series.thickness = 8
                series.title = foodType.displayName
                series.isDrawDataPoints = false
            })
        }

        viewport.isXAxisBoundsManual = true
        viewport.setMinX(0.0)
        viewport.setMaxX(240.0)
        viewport.isYAxisBoundsManual = true
        viewport.setMinY(0.0)
        viewport.setMaxY(8.0)
        gridLabelRenderer.numHorizontalLabels = 9
        gridLabelRenderer.horizontalAxisTitle = "[мин]"
        gridLabelRenderer.verticalAxisTitle = "% влияния за 5 мин"

        val nf = NumberFormat.getInstance().apply { maximumFractionDigits = 1 }
        gridLabelRenderer.labelFormatter = DefaultLabelFormatter(nf, nf)
        legendRenderer.isVisible = true
        legendRenderer.resetStyles()
    }
}

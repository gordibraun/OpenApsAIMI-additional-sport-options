package app.aaps.wear.interaction.actions

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import app.aaps.core.interfaces.rx.events.EventWearToMobile
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.wear.R
import app.aaps.wear.interaction.utils.Constants.TARGET_DEFAULT_SHIFT_EXERCISE_MODE
import app.aaps.wear.interaction.utils.EditPlusMinusViewAdapter
import app.aaps.wear.interaction.utils.PlusMinusEditText
import app.aaps.wear.nondeprecated.GridPagerAdapterNonDeprecated
import java.text.DecimalFormat

class ExerciseModeActivity : ViewSelectorActivity() {

    val TAG = "ExerciseModeActivity"

    var editPercentage: PlusMinusEditText? = null
    var editDuration: PlusMinusEditText? = null
    var editTimeshift: PlusMinusEditText? = null

    var percentage = TARGET_DEFAULT_SHIFT_EXERCISE_MODE
    var timeshift = 0

    var isMGDL = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, WatchControlActivity::class.java).putExtra("kind", "ACTIVITY"))
        finish()
    }

    override fun onPause() {
        super.onPause()
        finish()
    }


    private inner class MyGridViewPagerAdapter : GridPagerAdapterNonDeprecated() {

        override fun getColumnCount(arg0: Int): Int {
            return 4
        }

        override fun getRowCount(): Int {
            return 1
        }

        override fun instantiateItem(container: ViewGroup, row: Int, col: Int): View = when (col) {
            // percentage
            0 -> {
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container)
                val view = viewAdapter.root
                val initValue = SafeParse.stringToDouble(editPercentage?.editText?.text.toString(), percentage)
                Log.d(TAG, "percentage init value = $initValue")

                editPercentage = PlusMinusEditText(
                    viewAdapter,
                    initValue,
                    30.0,
                    250.0,
                    1.0,
                    DecimalFormat("0"),
                    false,
                    getString(R.string.action_percentage)
                )

                container.addView(view)
                view.requestFocus()
                view
            }

            // duration
            1 -> {
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container)
                val view = viewAdapter.root
                val initValue = SafeParse.stringToDouble(editDuration?.editText?.text.toString(), 50.0)
                Log.d(TAG, "duration init value = $initValue")

                editDuration = PlusMinusEditText(
                    viewAdapter,
                    initValue,
                    0.0,
                    3 * 60.0,
                    5.0,
                    DecimalFormat("0"),
                    false,
                    getString(R.string.action_duration_minutes)
                )

                container.addView(view)
                // view.requestFocus()
                view
            }

            // timeshift
            2 -> {
                val viewAdapter = EditPlusMinusViewAdapter.getViewAdapter(sp, applicationContext, container)
                val view = viewAdapter.root
                val initValue = SafeParse.stringToDouble(editTimeshift?.editText?.text.toString(), timeshift.toDouble())

                editTimeshift = PlusMinusEditText(
                    viewAdapter,
                    initValue,
                    0.0,
                    90.0,
                    5.0,
                    DecimalFormat("0"),
                    true,
                    getString(R.string.action_timeshift_minutes),
                    true
                )
                container.addView(view)
                // view.requestFocus()
                view
            }

            else -> {
                val view = LayoutInflater.from(applicationContext).inflate(R.layout.action_confirm_ok, container, false)
                val confirmButton = view.findViewById<ImageView>(R.id.confirmbutton)

                confirmButton.setOnClickListener {
                    // check if it can happen that the fragment is never created that hold data?
                    // (you have to swipe past them anyways - but still)
                    val action = EventData.ActionExerciseMode(
                        SafeParse.stringToInt(editPercentage?.editText?.text.toString()),
                        SafeParse.stringToInt(editDuration?.editText?.text.toString()),
                        SafeParse.stringToInt(editTimeshift?.editText?.text.toString())
                    )

                    rxBus.send(EventWearToMobile(action))

                    showToast(this@ExerciseModeActivity, R.string.action_exercise_mode_confirmation)
                    finishAffinity()
                }

                container.addView(view)
                view
            }
        }

        override fun destroyItem(container: ViewGroup, row: Int, col: Int, view: Any) {
            // Handle this to get the data before the view is destroyed?
            // Object should still be kept by this, just setup for re-init?
            container.removeView(view as View)
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view === `object`
        }
    }
}

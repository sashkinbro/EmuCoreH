/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.tfmonitor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.tuningfork.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList


class AppDetailFragment : Fragment(), AdapterView.OnItemSelectedListener {
    private var listener: OnFragmentInteractionListener? = null

    private lateinit var appNameTextView: TextView
    private lateinit var appNumberTextView: TextView

    private lateinit var modeGroup: RadioGroup
    private lateinit var expertResponseParameters: View

    private lateinit var instrument_key_spinner: Spinner
    private lateinit var last_update_time: TextView
    private lateinit var x_min_label: TextView
    private lateinit var x_max_label: TextView
    private lateinit var getTuningParametersHttpResponseEdit : EditText
    private lateinit var uploadTelemetryHttpResponseEdit : EditText

    private var paused = false

    private var known_instrument_keys = ArrayList<Int>()

    private val TUNING_PARAMETERS_HTTP_RESPONSE_KEY = "getTuningParametersHttpResponse"
    private val UPLOAD_TELEMETRY_HTTP_RESPONSE_KEY = "uploadTelemetryHttpResponse"

    private lateinit var datastore: Datastore;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
        }
    }

    private fun getViewModel(): MonitorViewModel {
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(activity?.application!!)
        return ViewModelProvider(
            activity?.viewModelStore!!,
            factory
        ).get(MonitorViewModel::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_app_detail, container, false)

        appNameTextView = view.findViewById(R.id.app_name_text_view)
        appNameTextView.text = getViewModel().getActiveApp()?.name ?: "???"
        appNumberTextView = view.findViewById(R.id.app_number_text_view)
        appNumberTextView.text = getViewModel().getActiveApp()?.version?.toString() ?: "?"
        modeGroup = view.findViewById(R.id.modeGroup)
        expertResponseParameters = view.findViewById(R.id.expertResponseParameters)
        instrument_key_spinner = view.findViewById(R.id.instrument_key_spinner)
        instrument_key_spinner.onItemSelectedListener = this
        last_update_time = view.findViewById(R.id.last_update_time)
        x_min_label = view.findViewById(R.id.x_min_label)
        x_max_label = view.findViewById(R.id.x_max_label)
        getTuningParametersHttpResponseEdit = view.findViewById(R.id.startup_http_response)
        uploadTelemetryHttpResponseEdit = view.findViewById(R.id.telemetry_http_response)

        getTuningParametersHttpResponseEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(widget: Editable?) {
                getViewModel().getTuningParametersResponse =
                    getTuningParametersHttpResponseEdit.text.toString().toInt()
            }
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
        uploadTelemetryHttpResponseEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(widget: Editable?) {
                getViewModel().uploadTelemetryResponse =
                    uploadTelemetryHttpResponseEdit.text.toString().toInt()
            }
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })

        view.findViewById<ToggleButton>(R.id.play_pause_button).setOnClickListener { v ->
            paused = !(v as ToggleButton).isChecked
        }
        modeGroup.setOnCheckedChangeListener({ _, checked ->
            run {
                if (checked == R.id.scaledButton) {
                    expertResponseParameters.visibility = View.GONE;
                } else {
                    expertResponseParameters.visibility = View.VISIBLE;
                }
            }
        })
        val mode_retrieval_name = getViewModel().getActiveApp()?.name
        if (mode_retrieval_name != null && savedInstanceState != null) {
            modeGroup.check((savedInstanceState[mode_retrieval_name] ?: R.id.scaledButton) as Int)
        } else {
            modeGroup.check(R.id.scaledButton)
        }

        datastore = Datastore(this.context!!, getString(R.string.database_name))

        getViewModel().getActiveAppTelemetry()?.observe(this, Observer<AppTelemetry> { data ->
            updateFromDatabase(this.view!!)
        })

        updateFromDatabase(view)

        return view
    }

    fun updateFromDatabase(view: View) {
        updateInstrumentKeys(view)
        updateHistograms(view)
    }

    fun updateInstrumentKeys(view: View) {
        getViewModel().getActiveApp()?.let { app ->
            val ikeys = datastore.getInstrumentKeysForApp(app)
            for (key in ikeys)
                addInstrumentKey(key)
        }
    }

    fun addInstrumentKey(key: Int) {
        if (!known_instrument_keys.contains(key)) {
            known_instrument_keys.add(key)
            val spinnerArray = known_instrument_keys.map { x -> x.toString() }.toTypedArray()
            val spinnerArrayAdapter = ArrayAdapter<String>(
                this.context!!, android.R.layout.simple_spinner_item,
                spinnerArray
            ) //selected item will look like a spinner set from XML
            spinnerArrayAdapter.setDropDownViewResource(
                android.R.layout
                    .simple_spinner_dropdown_item
            )
            instrument_key_spinner.setAdapter(spinnerArrayAdapter)
        }
    }

    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        updateHistograms(this.view!!)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) {
    }

    fun localTimeString(t: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd hh:mm:ss")
        return format.format(t)
    }

    fun updateHistograms(view: View) {
        var widget = view.findViewById<HistogramWidget>(R.id.histogram)
        var barColour = ContextCompat.getColor(this.context!!, DEFAULT_COLOUR_ID)
        val ix = instrument_key_spinner.selectedItemId.toInt()
        if (ix < known_instrument_keys.size) {
            val key = known_instrument_keys[ix]
            val th = datastore.getLatestHistogramForInstrumentKey(key)
            if (th == null) {
                widget!!.setData(ArrayList<HistogramBarMetaData>(0))
            } else {
                val h = th.second
                var n = h.counts.size
                if (n > 0) {
                    last_update_time.text = localTimeString(th.first)
                    val barDataList = ArrayList<HistogramBarMetaData>(n)
                    var name = 0
                    var counts = h.counts!!;
                    var max_value = counts.max()?.toFloat() ?: 1.0f
                    for (q in 0 until n) {
                        var height = (counts[q].toFloat()) / max_value
                        val barData = HistogramBarMetaData(
                            q, // This is ignored inside setData
                            height,
                            barColour
                        )
                        barDataList.add(barData)
                        name++
                    }
                    widget!!.setData(barDataList)
                    // Update x-axis labels
                    x_min_label.text = "?????"
                    x_max_label.text = "?????"
                    getViewModel().getActiveApp()?.let {
                        datastore.getLatestSettingsForApp(it)?.let { settings ->
                            for (settings_h in settings.histogramsList) {
                                if (settings_h.instrumentKey == h.instrument_id) {
                                    x_min_label.text = settings_h.bucketMin.toString()
                                    x_max_label.text = settings_h.bucketMax.toString()
                                }
                            }
                        }
                    }
                }
            }
            widget.invalidate()
        }
    }

    override fun onStart() {
        super.onStart()
    }

    fun onButtonPressed(uri: Uri) {
        listener?.onFragmentInteraction(uri)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            listener = context
        } else {
            throw RuntimeException(context.toString()
                            + " must implement OnFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     *
     *
     * See the Android Training lesson [Communicating with Other Fragments]
     * (http://developer.android.com/training/basics/fragments/communicating.html)
     * for more information.
     */
    interface OnFragmentInteractionListener {
        fun onFragmentInteraction(uri: Uri)
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @return A new instance of fragment AppDetailFragment.
         */
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            AppDetailFragment().apply {
                arguments = Bundle().apply {
                    // No arguments
                }
            }

        private val DEFAULT_COLOUR_ID = R.color.colorAccent
    }
}

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
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.tuningfork.AppKey

class AppChoiceAdapter(val myDataset: MonitorViewModel) :
    RecyclerView.Adapter<AppChoiceAdapter.ViewHolder>() {

    enum class ViewHolderType {
        HEADING, ITEM
    }

    // Provide a reference to the views for each data item
    // Complex data items may need more than one view per item, and
    // you provide access to all the views for a data item in a view holder.
    // Each data item is just a string in this case that is shown in a TextView.
    class ViewHolder(val type: ViewHolderType, val dataset: MonitorViewModel, v: View) :
        RecyclerView.ViewHolder(v), View.OnClickListener {
        var appNameTextView: TextView? = null
        var appVersionTextView: TextView? = null
        var headingTextView: TextView? = null

        var appKey: AppKey? = null

        init {
            if (type == ViewHolderType.HEADING) {
                headingTextView = v.findViewById(R.id.headingTextView)
            } else {
                v.setOnClickListener(this)
                appNameTextView = v.findViewById(R.id.nameTextView)
                appVersionTextView = v.findViewById(R.id.versionTextView)
                (v.findViewById(R.id.app_delete_button) as View).setOnClickListener {
                    Log.d(TAG, "Delete $adapterPosition clicked.")
                }
            }
        }

        override fun onClick(v: View?) {
            dataset.setActiveApp(appKey)
            Navigation.findNavController(v!!).navigate(R.id.app_detail_fragment)
        }
    }

    // Create new views (invoked by the layout manager)
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppChoiceAdapter.ViewHolder {
        // create a new view
        val (type, view) = if (viewType == ViewHolderType.ITEM.ordinal) {
            Pair(
                ViewHolderType.ITEM, LayoutInflater.from(parent.context)
                    .inflate(R.layout.text_row_item, parent, false) as View
            )
        } else {
            Pair(
                ViewHolderType.HEADING, LayoutInflater.from(parent.context)
                    .inflate(R.layout.heading_row_item, parent, false) as View
            )
        }
        return ViewHolder(type, myDataset, view)
    }

    // Replace the contents of a view (invoked by the layout manager)
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // - get element from your dataset at this position
        // - replace the contents of the view with that element
        if (getItemViewType(position) == ViewHolderType.HEADING.ordinal) {
            if (position == 0)
                holder.headingTextView?.text = "Live Applications"
            else
                holder.headingTextView?.text = "Saved Applications"
        } else {
            val app = getApp(position)
            holder.appNameTextView?.text = app?.name ?: ""
            holder.appVersionTextView?.text = app?.version.toString()
            holder.appKey = app
        }
    }

    fun numLiveApps() = (myDataset.getLiveApps().value?.size ?: 0)

    fun numStoredApps() = (myDataset.getStoredApps().value?.size ?: 0)

    fun getApp(position: Int): AppKey? {
        if (position == 0 || position - 1 == numLiveApps()) return null
        if (position - 1 < numLiveApps()) {
            return myDataset.getLiveApps().value?.get(position - 1)
        } else {
            return myDataset.getStoredApps().value?.get(position - 2 - numLiveApps())
        }
    }

    // Return the size of your dataset (invoked by the layout manager)
    override fun getItemCount() = 2 + numLiveApps() + numStoredApps()

    override fun getItemViewType(position: Int): Int {
        if (position == 0 || position - 1 == numLiveApps()) return ViewHolderType.HEADING.ordinal
        else return ViewHolderType.ITEM.ordinal
    }

    companion object {
        private val TAG = "AppChoiceAdapter"
    }
}

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [AppChoiceFragment.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [AppChoiceFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class AppChoiceFragment : Fragment() {
    private var listener: OnFragmentInteractionListener? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var viewAdapter: RecyclerView.Adapter<*>
    private lateinit var viewManager: RecyclerView.LayoutManager

    private lateinit var model: MonitorViewModel

    private fun getViewModel(): MonitorViewModel {
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(activity?.application!!)
        return ViewModelProvider(
            activity?.viewModelStore!!,
            factory
        ).get(MonitorViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = getViewModel()
        model.getLiveApps().observe(this, Observer<List<AppKey>> {
            viewAdapter.notifyDataSetChanged()
        })
        model.getStoredApps().observe(this, Observer<List<AppKey>> {
            viewAdapter.notifyDataSetChanged()
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_app_choice, container, false)

        viewManager = LinearLayoutManager(view.context)
        viewAdapter = AppChoiceAdapter(model)

        if (view != null) {
            recyclerView = view.findViewById<RecyclerView>(R.id.app_choice_recycler_view).apply {
                layoutManager = viewManager
                adapter = viewAdapter
            }
        }
        return view

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
            throw RuntimeException(context.toString() + " must implement OnFragmentInteractionListener")
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
         * @return A new instance of fragment AppChoiceFragment.
         */
        @JvmStatic
        fun newInstance() =
            AppChoiceFragment().apply {
                arguments = Bundle().apply {
                    // No arguments
                }
            }
    }
}

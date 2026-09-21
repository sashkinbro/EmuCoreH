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

import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.protobuf.DescriptorProtos

import com.google.tfmonitor.databinding.FragmentItemBinding;

import com.google.tfmonitor.FieldItemFragment.OnListFragmentInteractionListener

class FieldItemRecyclerViewAdapter(
    private val mValues: List<DescriptorProtos.FieldDescriptorProto>,
    private val mListener: OnListFragmentInteractionListener?
) : RecyclerView.Adapter<FieldItemRecyclerViewAdapter.ViewHolder>() {

    private val mOnClickListener: View.OnClickListener
    private var mBinding: FragmentItemBinding? = null;

    init {
        mOnClickListener = View.OnClickListener { v ->
            val item = v.tag as DescriptorProtos.FieldDescriptorProto
            // Notify the active callbacks interface (the activity, if the fragment is attached to
            // one) that an item has been selected.
            mListener?.onListFragmentInteraction(item)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        mBinding = FragmentItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false);
        return ViewHolder(mBinding!!.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mValues[position]
        holder.mNameView.text = item.name
        holder.mTypeView.text = item.typeName

        with(holder.mView) {
            tag = item
            setOnClickListener(mOnClickListener)
        }
    }

    override fun getItemCount(): Int = mValues.size

    inner class ViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
        val mNameView: TextView = mBinding!!.itemName
        val mTypeView: TextView = mBinding!!.itemType
        val mValueView: View? = null

        override fun toString(): String {
            return super.toString() + " '" + mNameView.toString() + "'"
        }
    }
}

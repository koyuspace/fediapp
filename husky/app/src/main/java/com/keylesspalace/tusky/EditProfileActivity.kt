/*
 * Husky -- A Pleroma client for Android
 *
 * Copyright (C) 2022  The Husky Developers
 * Copyright (C) 2017  Andrew Dawson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.keylesspalace.tusky

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.snackbar.Snackbar
import com.keylesspalace.tusky.adapter.AccountFieldEditAdapter
import com.keylesspalace.tusky.di.Injectable
import com.keylesspalace.tusky.di.ViewModelFactory
import com.keylesspalace.tusky.entity.Account
import com.keylesspalace.tusky.entity.Instance
import com.keylesspalace.tusky.util.*
import com.keylesspalace.tusky.viewmodel.EditProfileViewModel
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial
import com.mikepenz.iconics.utils.colorInt
import com.mikepenz.iconics.utils.sizeDp
import com.theartofdev.edmodo.cropper.CropImage
import kotlinx.android.synthetic.main.activity_edit_profile.*
import kotlinx.android.synthetic.main.toolbar_basic.*
import javax.inject.Inject

class EditProfileActivity : BaseActivity(), Injectable {

    companion object {
        const val AVATAR_SIZE = 400
        const val HEADER_WIDTH = 1500
        const val HEADER_HEIGHT = 500

        private const val AVATAR_PICK_RESULT = 1
        private const val HEADER_PICK_RESULT = 2
        private const val PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1
        private const val MASTODON_MAX_ACCOUNT_FIELDS = 4

        private const val BUNDLE_CURRENTLY_PICKING = "BUNDLE_CURRENTLY_PICKING"
    }

    @Inject
    lateinit var viewModelFactory: ViewModelFactory

    private val viewModel: EditProfileViewModel by viewModels { viewModelFactory }

    private var currentlyPicking: PickType = PickType.NOTHING

    private val accountFieldEditAdapter = AccountFieldEditAdapter()
    private var maxAccountFields = MASTODON_MAX_ACCOUNT_FIELDS

    private enum class PickType {
        NOTHING,
        AVATAR,
        HEADER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.getString(BUNDLE_CURRENTLY_PICKING)?.let {
            currentlyPicking = PickType.valueOf(it)
        }

        setContentView(R.layout.activity_edit_profile)

        setSupportActionBar(toolbar)
        supportActionBar?.run {
            setTitle(R.string.title_edit_profile)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        avatarButton.setOnClickListener { onMediaPick(PickType.AVATAR) }
        headerButton.setOnClickListener { onMediaPick(PickType.HEADER) }

        fieldList.layoutManager = LinearLayoutManager(this)
        fieldList.adapter = accountFieldEditAdapter

        val plusDrawable = IconicsDrawable(this, GoogleMaterial.Icon.gmd_add).apply { sizeDp = 12; colorInt = Color.WHITE }

        addFieldButton.setCompoundDrawablesRelativeWithIntrinsicBounds(plusDrawable, null, null, null)

        addFieldButton.setOnClickListener {
            accountFieldEditAdapter.addField()
            if(accountFieldEditAdapter.itemCount >= maxAccountFields) {
                it.isVisible = false
            }

            scrollView.post{
                scrollView.smoothScrollTo(0, it.bottom)
            }
        }

        viewModel.obtainProfile()

        viewModel.profileData.observe(this) { profileRes ->
            when (profileRes) {
                is Success -> {
                    val me = profileRes.data
                    if (me != null) {

                        displayNameEditText.setText(me.displayName)
                        noteEditText.setText(me.source?.note)
                        lockedCheckBox.isChecked = me.locked

                        accountFieldEditAdapter.setFields(me.source?.fields ?: emptyList())
                        addFieldButton.isEnabled = me.source?.fields?.size ?: 0 < maxAccountFields

                        if(viewModel.avatarData.value == null) {
                            Glide.with(this)
                                    .load(me.avatar)
                                    .placeholder(R.drawable.avatar_default)
                                    .transform(
                                            FitCenter(),
                                            RoundedCorners(resources.getDimensionPixelSize(R.dimen.avatar_radius_80dp))
                                    )
                                    .into(avatarPreview)
                        }

                        if(viewModel.headerData.value == null) {
                            Glide.with(this)
                                    .load(me.header)
                                    .into(headerPreview)
                        }

                    }
                }
                is Error -> {
                    val snackbar = Snackbar.make(avatarButton, R.string.error_generic, Snackbar.LENGTH_LONG)
                    snackbar.setAction(R.string.action_retry) {
                        viewModel.obtainProfile()
                    }
                    snackbar.show()

                }
                else -> {}
            }
        }

        viewModel.obtainInstance()
        viewModel.instanceData.observe(this) { result ->
            when (result) {
                is Success -> {
                    val instance = result.data
                    if (instance?.maxBioChars != null && instance.maxBioChars > 0) {
                        noteEditTextLayout.counterMaxLength = instance.maxBioChars
                    }

                    instance?.pleroma?.metadata?.fieldsLimits?.let {
                        maxAccountFields = it.maxFields

                        if(maxAccountFields > MASTODON_MAX_ACCOUNT_FIELDS
                            && accountFieldEditAdapter.itemCount == MASTODON_MAX_ACCOUNT_FIELDS
                            && !addFieldButton.isEnabled) {
                            addFieldButton.isEnabled = true
                        }
                    }
                }
                else -> {}
            }
        }

        observeImage(viewModel.avatarData, avatarPreview, avatarProgressBar, true)
        observeImage(viewModel.headerData, headerPreview, headerProgressBar, false)

        viewModel.saveData.observe(this) {
            when(it) {
                is Success -> {
                    finish()
                }
                is Loading -> {
                    saveProgressBar.visibility = View.VISIBLE
                }
                is Error -> {
                    onSaveFailure(it.errorMessage)
                }
            }
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(BUNDLE_CURRENTLY_PICKING, currentlyPicking.toString())
    }

    override fun onStop() {
        super.onStop()
        if(!isFinishing) {
            viewModel.updateProfile(displayNameEditText.text.toString(),
                    noteEditText.text.toString(),
                    lockedCheckBox.isChecked,
                    accountFieldEditAdapter.getFieldData())
        }
    }

    private fun observeImage(liveData: LiveData<Resource<Bitmap>>,
                             imageView: ImageView,
                             progressBar: View,
                             roundedCorners: Boolean) {
        liveData.observe(this, Observer<Resource<Bitmap>> {

            when (it) {
                is Success -> {
                    val glide = Glide.with(imageView)
                            .load(it.data)

                            if (roundedCorners) {
                                glide.transform(
                                        FitCenter(),
                                        RoundedCorners(resources.getDimensionPixelSize(R.dimen.avatar_radius_80dp))
                                )
                            }

                            glide.into(imageView)

                    imageView.show()
                    progressBar.hide()
                }
                is Loading -> {
                    progressBar.show()
                }
                is Error -> {
                    progressBar.hide()
                    if(!it.consumed) {
                        onResizeFailure()
                        it.consumed = true
                    }

                }
            }
        })
    }

    private fun onMediaPick(pickType: PickType) {
        if (currentlyPicking != PickType.NOTHING) {
            // Ignore inputs if another pick operation is still occurring.
            return
        }
        currentlyPicking = pickType
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE)
        } else {
            initiateMediaPicking()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initiateMediaPicking()
                } else {
                    endMediaPicking()
                    Snackbar.make(avatarButton, R.string.error_media_upload_permission, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun initiateMediaPicking() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        when (currentlyPicking) {
            PickType.AVATAR -> {
                startActivityForResult(intent, AVATAR_PICK_RESULT)
            }
            PickType.HEADER -> {
                startActivityForResult(intent, HEADER_PICK_RESULT)
            }
            PickType.NOTHING -> { /* do nothing */ }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.edit_profile_toolbar, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            R.id.action_save -> {
                save()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun save() {
        if (currentlyPicking != PickType.NOTHING) {
               return
        }

        viewModel.save(displayNameEditText.text.toString(),
                noteEditText.text.toString(),
                lockedCheckBox.isChecked,
                accountFieldEditAdapter.getFieldData(),
                this)
    }

    private fun onSaveFailure(msg: String?) {
        val errorMsg = msg ?: getString(R.string.error_media_upload_sending)
        Snackbar.make(avatarButton, errorMsg, Snackbar.LENGTH_LONG).show()
        saveProgressBar.visibility = View.GONE
    }

    private fun beginMediaPicking() {
        when (currentlyPicking) {
            PickType.AVATAR -> {
                avatarProgressBar.visibility = View.VISIBLE
                avatarPreview.visibility = View.INVISIBLE
                avatarButton.setImageDrawable(null)

            }
            PickType.HEADER -> {
                headerProgressBar.visibility = View.VISIBLE
                headerPreview.visibility = View.INVISIBLE
                headerButton.setImageDrawable(null)
            }
            PickType.NOTHING -> { /* do nothing */ }
        }
    }

    private fun endMediaPicking() {
        avatarProgressBar.visibility = View.GONE
        headerProgressBar.visibility = View.GONE

        currentlyPicking = PickType.NOTHING
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            AVATAR_PICK_RESULT -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    CropImage.activity(data.data)
                            .setInitialCropWindowPaddingRatio(0f)
                            .setOutputCompressFormat(Bitmap.CompressFormat.PNG)
                            .setAspectRatio(AVATAR_SIZE, AVATAR_SIZE)
                            .start(this)
                } else {
                    endMediaPicking()
                }
            }
            HEADER_PICK_RESULT -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    CropImage.activity(data.data)
                            .setInitialCropWindowPaddingRatio(0f)
                            .setOutputCompressFormat(Bitmap.CompressFormat.PNG)
                            .setAspectRatio(HEADER_WIDTH, HEADER_HEIGHT)
                            .start(this)
                } else {
                    endMediaPicking()
                }
            }
            CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE -> {
                val result = CropImage.getActivityResult(data)
                when (resultCode) {
                    Activity.RESULT_OK -> beginResize(result.uri)
                    CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE -> onResizeFailure()
                    else -> endMediaPicking()
                }
            }
        }
    }

    private fun beginResize(uri: Uri) {
        beginMediaPicking()

        when (currentlyPicking) {
            PickType.AVATAR -> {
                viewModel.newAvatar(uri, this)
            }
            PickType.HEADER -> {
                viewModel.newHeader(uri, this)
            }
            else -> {
                throw AssertionError("PickType not set.")
            }
        }

        currentlyPicking = PickType.NOTHING

    }

    private fun onResizeFailure() {
        Snackbar.make(avatarButton, R.string.error_media_upload_sending, Snackbar.LENGTH_LONG).show()
        endMediaPicking()
    }

}

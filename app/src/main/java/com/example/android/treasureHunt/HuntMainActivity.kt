/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.treasureHunt

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.BuildConfig
import com.example.android.treasureHunt.BuildConfig.APPLICATION_ID
import com.example.android.treasureHunt.GeofencingConstants
import com.example.android.treasureHunt.R
import com.example.android.treasureHunt.createChannel
import com.example.android.treasureHunt.databinding.ActivityHuntMainBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.material.snackbar.Snackbar

/**
 * The Treasure Hunt app is a single-player game based on geofences.
 *
 * This app demonstrates how to create and remove geofences using the GeofencingApi. Uses an
 * BroadcastReceiver to monitor geofence transitions and creates notification and finishes the game
 * when the user enters the final geofence (destination).
 *
 * This app requires a device's Location settings to be turned on. It also requires
 * the ACCESS_FINE_LOCATION permission and user consent. For geofences to work
 * in Android Q, app also needs the ACCESS_BACKGROUND_LOCATION permission and user consent>>???????????? ????????????.
 */
@SuppressLint("UnspecifiedImmutableFlag")
@RequiresApi(Build.VERSION_CODES.Q)
class HuntMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHuntMainBinding
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var viewModel: GeofenceViewModel

    //Step 2 add in variable to check if device is running Q>>29 or later
    private val runningQOrLater:Boolean=Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // A PendingIntent for the Broadcast Receiver that handles geofence transitions.
    // Step 8 add in a pending intent
    private val geofencePendingIntent by lazy {
        val intent=Intent(this,GeofenceBroadcastReceiver::class.java)
        intent.action= ACTION_GEOFENCE_EVENT
        PendingIntent.getBroadcast(this,0,intent,PendingIntent.FLAG_UPDATE_CURRENT)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_hunt_main)
        viewModel = ViewModelProvider(this, SavedStateViewModelFactory(this.application,
            this)).get(GeofenceViewModel::class.java)
        binding.viewmodel = viewModel
        binding.lifecycleOwner = this
        // Step 9 instantiate the geofencing client
        geofencingClient= LocationServices.getGeofencingClient(this)
        // Create channel for notifications
        createChannel(this )
    }


    override fun onStart() {
        super.onStart()
        checkPermissionsAndStartGeofencing()
    }


    /**
     * This will also destroy any saved state in the associated ViewModel, so we remove the
     * geofences here.
     */
    override fun onDestroy() {
        super.onDestroy()
        removeGeofences()
    }

    /**
     * Starts the permission check and Geofence process only if the Geofence associated with the
     * current hint isn't yet active.
     */
    private fun checkPermissionsAndStartGeofencing() {
        if (viewModel.geofenceIsActive()) return
        if (foregroundAndBackgroundLocationPermissionApproved()) {
            checkDeviceLocationSettingsAndStartGeofence()
        } else {
            requestForegroundAndBackgroundLocationPermissions()
        }
    }

    /*
       Uses the Location Client to check the current state of location settings, and gives the user
       the opportunity to turn on location services within our app.
           Note:
           this function responsible for the location itself not the permission
           if you turn the location off you will find this snack bar error appear
           add code to check that the device's location is on
     */

    private fun checkDeviceLocationSettingsAndStartGeofence(resolve:Boolean = true) {

       val locationRequest=LocationRequest.create().apply {
           priority=LocationRequest.PRIORITY_LOW_POWER
       }
       val builder=LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
       val settingsClient=LocationServices.getSettingsClient(this)
       val locationSettingsResponseTask=settingsClient.checkLocationSettings(builder.build())

        locationSettingsResponseTask.addOnFailureListener{exception->
            if (exception is ResolvableApiException && resolve){
                try {
                    // try calling the startResolutionForResult() method in order to prompt the user to turn on device location.
                    exception.startResolutionForResult(this@HuntMainActivity, REQUEST_TURN_DEVICE_LOCATION_ON)
                }catch (sendExcep:IntentSender.SendIntentException){
                    Log.e(TAG,"Error getting location settings resolution: ${sendExcep.message}")
                }
            }else{
                Snackbar.make(binding.activityMapsMain,R.string.location_required_error,Snackbar.LENGTH_INDEFINITE)
                    .setAction(android.R.string.ok){
                        checkDeviceLocationSettingsAndStartGeofence()
                    }
                    .show()
            }
        }//add on failure
        locationSettingsResponseTask.addOnCompleteListener{
            if (it.isSuccessful){
                addGeofenceForClue()
            }
        }


    }//end of checkDeviceLocationSettingsAndStartGeofence()


   /*
   *  When we get the result from asking the user to turn on device location, we call
   *  checkDeviceLocationSettingsAndStartGeofence again to make sure it's actually on, but
   *  we don't resolve the check to keep the user from seeing an endless loop.
   */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // add code to check that the user turned on their device location and ask
        //  again if they did not
        if (requestCode== REQUEST_TURN_DEVICE_LOCATION_ON){
            checkDeviceLocationSettingsAndStartGeofence(false)
        }
    }




    /*
     *  When the user clicks on the notification, this method will be called, letting us know that
     *  the geofence has been triggered, and it's time to move to the next one in the treasure
     *  hunt.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val extras = intent?.extras
        if(extras != null){
            if(extras.containsKey(GeofencingConstants.EXTRA_GEOFENCE_INDEX)){
                viewModel.updateHint(extras.getInt(GeofencingConstants.EXTRA_GEOFENCE_INDEX))
                checkPermissionsAndStartGeofencing()
            }
        }
    }
    /*
     *  Determines whether the app has the appropriate permissions across Android 10+ and all other
     *  Android versions.
     */

    private fun foregroundAndBackgroundLocationPermissionApproved(): Boolean {
       val foregroundLocationApproved:Boolean=
           (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==PackageManager.PERMISSION_GRANTED)
       val backgroundPermissionApproved:Boolean=
            if (runningQOrLater){
                (ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_BACKGROUND_LOCATION)== PackageManager.PERMISSION_GRANTED )
            }else{
                //that means that the version of sdk is less than Q so i don't have to ask for background permission.
                true
            }
        return foregroundLocationApproved&&backgroundPermissionApproved
    }

    /*
     *  Requests ACCESS_FINE_LOCATION and (on Android 10+ (Q) ACCESS_BACKGROUND_LOCATION.
     */


    private fun requestForegroundAndBackgroundLocationPermissions() {
        if (foregroundAndBackgroundLocationPermissionApproved())
            //I don't need to request any permission,so i will exit from this fun.
            return
        var permissionsArray = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION
            )//foreground location permission
        val resultCode = when {
            runningQOrLater==true -> {
                //I will get here if the sdk is q and above only
                permissionsArray += Manifest.permission.ACCESS_BACKGROUND_LOCATION
                permissionsArray += Manifest.permission.ACCESS_COARSE_LOCATION
                REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE
            }
            // if runningQOrLater==false the sdk is before Q>>api 29
            else -> REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
        }
        Log.d(TAG, "Request foreground only location permission")
        ActivityCompat.requestPermissions(
            this@HuntMainActivity,
            permissionsArray,
            resultCode
        )
    }

    /*
    * In all cases, we need to have the location permission.  On Android 10+ (Q) we need to have
    * the background permission as well.
    */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        Log.d(TAG, "onRequestPermissionResult")
        if (
            grantResults.isEmpty() ||
            grantResults[0] == PackageManager.PERMISSION_DENIED ||
            (   requestCode == REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE &&
                grantResults[1] ==PackageManager.PERMISSION_DENIED
                    )
            )
        {
            Snackbar.make(
                binding.activityMapsMain,
                R.string.permission_denied_explanation,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.settings) {
                    startActivity(Intent().apply {
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = Uri.fromParts("package",APPLICATION_ID
                            , null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }.show()
        } else {
            //means that the user accept the permissions
            checkDeviceLocationSettingsAndStartGeofence()
        }
    }

    /*
    * Adds a Geofence for the current clue if needed, and removes any existing Geofence. This
    * method should be called after the user has granted the location permission.  If there are
    * no more geofences, we remove the geofence and let the viewmodel know that the ending hint
    * is now "active."
    */
    private fun addGeofenceForClue() {


        if (viewModel.geofenceIsActive()) return
        //I want to active a geofence here
        val currentGeofenceIndex = viewModel.nextGeofenceIndex()
        if(currentGeofenceIndex >= GeofencingConstants.NUM_LANDMARKS) {//NUM_LANDMARKS >> 4
            removeGeofences()
            viewModel.geofenceActivated()
            return
        }
        val currentGeofenceData = GeofencingConstants.LANDMARK_DATA[currentGeofenceIndex]

        val geofence = Geofence.Builder()
            .setRequestId(currentGeofenceData.id)
            .setCircularRegion(currentGeofenceData.latLong.latitude,
                currentGeofenceData.latLong.longitude,
                GeofencingConstants.GEOFENCE_RADIUS_IN_METERS
            )
            .setExpirationDuration(GeofencingConstants.GEOFENCE_EXPIRATION_IN_MILLISECONDS)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.removeGeofences(geofencePendingIntent)?.run {
            addOnCompleteListener {
                if (ActivityCompat.checkSelfPermission(
                        this@HuntMainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@addOnCompleteListener
                }
                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)?.run {
                    addOnSuccessListener {
                        Toast.makeText(this@HuntMainActivity, R.string.geofences_added,
                            Toast.LENGTH_SHORT)
                            .show()
                        Log.e("Add Geofence", geofence.requestId)
                        viewModel.geofenceActivated()
                    }
                    addOnFailureListener {
                        Toast.makeText(this@HuntMainActivity, R.string.geofences_not_added,
                            Toast.LENGTH_SHORT).show()
                        if ((it.message != null)) {
                            Log.w(TAG, it.message!!)
                        }
                    }
                }
            }
        }
    }//addGeofenceForClue()

    /**
     * Removes geofences. This method should be called after the user has granted the location
     * permission.
     */
    private fun removeGeofences() {
        if (!foregroundAndBackgroundLocationPermissionApproved()) {
            return
        }
        geofencingClient.removeGeofences(geofencePendingIntent)?.run {
            addOnSuccessListener {
                Log.d(TAG, getString(R.string.geofences_removed))
                Toast.makeText(applicationContext, R.string.geofences_removed, Toast.LENGTH_SHORT)
                    .show()
            }
            addOnFailureListener {
                Log.d(TAG, getString(R.string.geofences_not_removed))
            }
        }
    }
    companion object {
        internal const val ACTION_GEOFENCE_EVENT =
            "HuntMainActivity.treasureHunt.action.ACTION_GEOFENCE_EVENT"
    }
}
    private const val REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE = 33
    private const val REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE = 34
    private const val REQUEST_TURN_DEVICE_LOCATION_ON = 29
    private const val TAG = "HuntMainActivity"
    private const val LOCATION_PERMISSION_INDEX = 0
    private const val BACKGROUND_LOCATION_PERMISSION_INDEX = 1

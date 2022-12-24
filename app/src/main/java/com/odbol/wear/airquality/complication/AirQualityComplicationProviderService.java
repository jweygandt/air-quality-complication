// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.odbol.wear.airquality.complication;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.location.Address;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.ComplicationManager;
import android.support.wearable.complications.ComplicationProviderService;
import android.support.wearable.complications.ComplicationText;
import android.support.wearable.complications.ComplicationTextUtils;
import android.text.TextUtils;
import android.util.Log;

import com.odbol.wear.airquality.AqiUtils;
import com.odbol.wear.airquality.R;
import com.odbol.wear.airquality.SensorDetailsActivity;
import com.odbol.wear.airquality.SensorStore;
import com.odbol.wear.airquality.purpleair.PurpleAir;
import com.odbol.wear.airquality.purpleair.Sensor;
import com.patloew.rxlocation.RxLocation;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

public class AirQualityComplicationProviderService extends ComplicationProviderService {

    private static final String TAG = "AirQualityComplication";

    private RxLocation rxLocation;
    private final CompositeDisposable subscriptions = new CompositeDisposable();

    private PurpleAir purpleAir;
    private SensorStore sensorStore;

    @Override
    public void onCreate() {
        super.onCreate();

        sensorStore = new SensorStore(this);
        purpleAir = new PurpleAir(this);
        rxLocation = new RxLocation(this);
    }

    @Override
    public void onDestroy() {
        subscriptions.dispose();
        super.onDestroy();
    }

    @Override
    public void onComplicationUpdate(int complicationId, int dataType, ComplicationManager manager) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onComplicationUpdate() id: " + complicationId);
        }

        final Single<Sensor> task;
        int sensorId = sensorStore.getSelectedSensorId();
        if (sensorId >= 0) {
////            FusedLocation locationProvider = rxLocation.location();
////            task = locationProvider
////                    .isLocationAvailable()
////                    .subscribeOn(Schedulers.io())
////                    .flatMapObservable((hasLocation) -> hasLocation ?
////                            locationProvider.lastLocation().toObservable() :
////                            locationProvider.updates(createLocationRequest()))
//                    .flatMapSingle(purpleAir::getSensor())
//                    .map(AqiUtils::throwIfInvalid);
            task = purpleAir.loadSensor(sensorId)
                    .map(AqiUtils::throwIfInvalid);
        } else {
            task = Single.error(new SecurityException("No sensor selected"));
        }

        subscriptions.add(
            task
                .subscribe(
                        // onNext
                        (sensor -> updateComplication(complicationId, dataType, manager, sensor)),
                        // onError
                        (error) -> {
                            Log.e(TAG, "Error retreiving location", error);
                            updateComplication(complicationId, dataType, manager, null);
                        }
                )
        );
    }

    private void updateComplication(int complicationId, int dataType, ComplicationManager manager, Sensor sensor) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "sensor: " + sensor);
        }

        ComplicationData complicationData = null;
        switch (dataType) {
            case ComplicationData.TYPE_SHORT_TEXT:
                complicationData =
                        new ComplicationData.Builder(ComplicationData.TYPE_SHORT_TEXT)
                                .setShortTitle(ComplicationText.plainText("AQI"))
                                .setShortText(getAqi(sensor))
                                .setContentDescription(getFullDescription(sensor))
                                .setIcon(Icon.createWithResource(this, R.drawable.ic_air_quality))
                                .setTapAction(getTapAction())
                                .build();
                break;
            case ComplicationData.TYPE_LONG_TEXT:
                complicationData =
                        new ComplicationData.Builder(ComplicationData.TYPE_LONG_TEXT)
                                .setLongTitle(getTimeAgo(sensor))
                                .setLongText(getAqi(sensor))
                                .setContentDescription(getFullDescription(sensor))
                                .setIcon(Icon.createWithResource(this, R.drawable.ic_air_quality))
                                .setTapAction(getTapAction())
                                .build();
                break;
            case ComplicationData.TYPE_RANGED_VALUE: {
                float mx, mn;
                int color;
                float value = getAqiValue(sensor);
                if(value <=50){
                    mn=0;
                    mx=50;
                    color=0;
                } else if (value <=100){
                    mn=50;
                    mx=100;
                    color=1;
                } else if (value <=150){
                    mn=100;
                    mx=150;
                    color=2;
                } else if (value <=200){
                    mn=150;
                    mx=200;
                    color=3;
                } else if (value <=300){
                    mn=200;
                    mx=300;
                    color=4;
                } else {
                    mn=300;
                    mx=400;
                    color=5;
                }
                complicationData =
                        new ComplicationData.Builder(ComplicationData.TYPE_RANGED_VALUE)
//                                .setShortTitle(getTimeAgo(sensor))
                                .setShortText(getAqi(sensor))
                                .setImageContentDescription(ComplicationText.plainText(
                                        ((int)value) + " ppm" + "#Color:"+color))
                                .setMinValue(mn)
                                .setMaxValue(mx)
                                .setValue(value)
//                                .setContentDescription(getFullDescription(sensor))
                                .setIcon(Icon.createWithResource(this, R.drawable.ic_air_quality))
                                .setTapAction(getTapAction())
                                .build();
                break;
            }
            default:
                if (Log.isLoggable(TAG, Log.WARN)) {
                    Log.w(TAG, "Unexpected complication type " + dataType);
                }
        }

        if (complicationData != null) {
            manager.updateComplicationData(complicationId, complicationData);
        } else {
            // If no data is sent, we still need to inform the ComplicationManager, so
            // the update job can finish and the wake lock isn't held any longer.
            manager.noUpdateRequired(complicationId);
        }
    }

    private int getAqiValue(Sensor sensor) {
        if (sensor == null) return 0;
        return AqiUtils.convertPm25ToAqi(sensor.getPm25()).getAQI();
    }

    @NotNull
    private ComplicationText getTimeAgo(Sensor sensor) {
        if (sensor == null) return ComplicationText.plainText("--");
        return getTimeAgo(sensor.getLastSeenSeconds()).build();
    }

    private ComplicationText getAqi(Sensor sensor) {
        if (sensor == null) return ComplicationText.plainText("--");
        return ComplicationText.plainText(String.valueOf(getAqiValue(sensor)));
    }

    private PendingIntent getTapAction() {
        Intent intent = new Intent(this, SensorDetailsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private ComplicationText getFullDescription(Sensor sensor) {
        if (sensor == null) return ComplicationText.plainText(getString(R.string.no_location));

        return getTimeAgo(sensor.getLastSeenSeconds())
                .setSurroundingText(getString(R.string.aqi_as_of_time_ago, getAqiValue(sensor), "^1"))
                .build();
    }

    public static ComplicationText getAddressDescriptionText(Context context, Address address) {
        return ComplicationText.plainText(getAddressDescription(context, address));
    }

    public static String getAddressDescription(Context context, Address address) {
        if (address == null) return context.getString(R.string.no_location);
        String subThoroughfare = address.getSubThoroughfare();
        String thoroughfare = address.getThoroughfare();
        if (thoroughfare == null) return address.toString();
        return (TextUtils.isEmpty(subThoroughfare) ? "" : subThoroughfare +  " ") + thoroughfare;
    }

    private ComplicationText.TimeDifferenceBuilder getTimeAgo(Long fromTime) {
        return new ComplicationText.TimeDifferenceBuilder()
                .setStyle(ComplicationText.DIFFERENCE_STYLE_SHORT_SINGLE_UNIT)
                .setMinimumUnit(TimeUnit.MINUTES)
                .setReferencePeriodEnd(fromTime == null ? 0 : fromTime)
                .setShowNowText(true);
    }
}

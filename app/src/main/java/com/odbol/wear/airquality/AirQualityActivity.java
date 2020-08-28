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

package com.odbol.wear.airquality;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.wearable.complications.ProviderUpdateRequester;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.gms.location.LocationRequest;
import com.odbol.wear.airquality.complication.AirQualityComplicationProviderService;
import com.odbol.wear.airquality.purpleair.PurpleAir;
import com.odbol.wear.airquality.purpleair.Sensor;
import com.patloew.rxlocation.RxLocation;
import com.tbruyelle.rxpermissions2.RxPermissions;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

import static com.odbol.wear.airquality.purpleair.PurpleAirServiceKt.sortByClosest;

public class AirQualityActivity extends FragmentActivity {

    private static final String TAG = "AirQualityActivity";

    private static final long PROGRESS_UPDATE_INTERVAL_SECONDS = 1;
    private static final long PROGRESS_UPDATE_TOTAL_SECONDS = TimeUnit.MINUTES.toSeconds(2);

    int MAX_SENSORS_IN_LIST = 100;

    private final CompositeDisposable subscriptions = new CompositeDisposable();
    private final CompositeDisposable loadingSubscription = new CompositeDisposable();

    private View loadingView;
    private TextView textView;
    private RecyclerView listView;
    private ProgressBar progressBar;

    private RxLocation rxLocation;

    private PurpleAir purpleAir;

    private final SensorsAdapter adapter = new SensorsAdapter(this::onSensorSelected);

    private SensorStore sensorStore;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.where_am_i_activity);

        purpleAir = new PurpleAir(this);
        sensorStore = new SensorStore(this);

        textView = (TextView) findViewById(R.id.text);
        progressBar = (ProgressBar) findViewById(R.id.progress);
        loadingView = (View) findViewById(R.id.loading);
        listView = (RecyclerView) findViewById(R.id.list);

        listView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter);

        int selectedSensorId = sensorStore.getSelectedSensorId();

        startLoading();

        rxLocation = new RxLocation(this);
        subscriptions.add(
            checkPermissions()
                    .subscribeOn(Schedulers.io())
                    .flatMap((isGranted) -> rxLocation.location().updates(createLocationRequest()))
                    .debounce(3, TimeUnit.SECONDS)
                    .flatMap(this::findSensorsForLocation)
                    .take(MAX_SENSORS_IN_LIST)
                    .map(sensor -> {
                        if (sensor.getID() == selectedSensorId) {
                            sensor.setSelected(true);
                        }
                        return sensor;
                    })
                    .toSortedList((a, b) -> {
                        if (a.isSelected()) return 1;
                        if (b.isSelected()) return -1;
                        return 0;
                    }, MAX_SENSORS_IN_LIST)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        // onNext
                        (sensors) -> {
                            loadingView.setVisibility(View.GONE);
                            listView.setVisibility(View.VISIBLE);

                            adapter.setSensors(sensors);

                            doneLoading();
                        },
                        // onError
                        (error) -> {
                            Log.e(TAG, "Error:", error);
                            textView.setText(R.string.location_error);
                            doneLoading();
                        }
                    )
        );
    }

    private Observable<Sensor> findSensorsForLocation(Location location) {
        return purpleAir.getAllSensors()
                .subscribeOn(Schedulers.io())
                .doOnNext((s) -> progressBar.post(() -> {
                    //Log.d(TAG, "Done loading sensors " + progressBar.getProgress());
                    if (progressBar.getProgress() < 60) {
                        progressBar.setProgress(60, true);
                    }
                }))
                .sorted(sortByClosest(location));
    }

    private void doneLoading() {
        loadingView.setKeepScreenOn(false);

        loadingSubscription.clear();
    }

    private void startLoading() {
        loadingView.setKeepScreenOn(true);
        progressBar.setProgress(0);

        loadingSubscription.clear();

        loadingSubscription.add(Observable.interval(PROGRESS_UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS, Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(time -> progressBar.incrementProgressBy((int) Math.round(/*Math.random() * */(float)progressBar.getMax() * ((float)PROGRESS_UPDATE_INTERVAL_SECONDS / (float)PROGRESS_UPDATE_TOTAL_SECONDS))))
        );
    }

    private void onSensorSelected(Sensor sensor) {
        sensorStore.setSelectedSensorId(sensor.getID());
        forceComplicationUpdate();
    }

    @Override
    public void onDestroy() {
        subscriptions.dispose();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        forceComplicationUpdate();
        super.onStop();
    }

    private void forceComplicationUpdate() {
        if (checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            new ProviderUpdateRequester(this, new ComponentName(this, AirQualityComplicationProviderService.class))
                    .requestUpdateAll();
        }
    }

    private Observable<Boolean> checkPermissions() {
        return new RxPermissions(this)
                .request(Manifest.permission.ACCESS_FINE_LOCATION)
                .map(isGranted -> {
                    if (isGranted) return true;
                    throw new SecurityException("No location permission");
                });
    }

    private CharSequence getTimeAgo(long time) {
        return DateUtils.getRelativeTimeSpanString(time);
    }


    private static LocationRequest createLocationRequest() {
        return LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(TimeUnit.SECONDS.toMillis(10))
                .setSmallestDisplacement(50);
    }
}

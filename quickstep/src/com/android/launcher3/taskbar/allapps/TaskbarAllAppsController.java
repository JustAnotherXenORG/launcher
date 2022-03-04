/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.launcher3.taskbar.allapps;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.launcher3.AbstractFloatingView.TYPE_ALL;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.appprediction.PredictionRowView;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.taskbar.TaskbarActivityContext;
import com.android.launcher3.taskbar.TaskbarControllers;

import java.util.List;
import java.util.Optional;

/**
 * Handles the all apps overlay window initialization, updates, and its data.
 * <p>
 * All apps is in an application overlay window instead of taskbar's navigation bar panel window,
 * because a navigation bar panel is higher than UI components that all apps should be below such as
 * the notification tray.
 * <p>
 * The all apps window is created and destroyed upon opening and closing all apps, respectively.
 * Application data may be bound while the window does not exist, so this controller will store
 * the models for the next all apps session.
 */
public final class TaskbarAllAppsController implements OnDeviceProfileChangeListener {

    private static final String WINDOW_TITLE = "Taskbar All Apps";

    private final TaskbarActivityContext mTaskbarContext;
    private final TaskbarAllAppsProxyView mProxyView;
    private final LayoutParams mLayoutParams;

    private TaskbarControllers mControllers;
    /** Window context for all apps if it is open. */
    private @Nullable TaskbarAllAppsContext mAllAppsContext;

    // Application data models.
    private AppInfo[] mApps;
    private int mAppsModelFlags;
    private List<ItemInfo> mPredictedApps;

    public TaskbarAllAppsController(TaskbarActivityContext context) {
        mTaskbarContext = context;
        mProxyView = new TaskbarAllAppsProxyView(mTaskbarContext);
        mLayoutParams = createLayoutParams();
    }

    /** Initialize the controller. */
    public void init(TaskbarControllers controllers) {
        if (FeatureFlags.ENABLE_ALL_APPS_IN_TASKBAR.get()) {
            mControllers = controllers;
        }
    }

    /** Updates the current {@link AppInfo} instances. */
    public void setApps(AppInfo[] apps, int flags) {
        if (!FeatureFlags.ENABLE_ALL_APPS_IN_TASKBAR.get()) {
            return;
        }

        mApps = apps;
        mAppsModelFlags = flags;
        if (mAllAppsContext != null) {
            mAllAppsContext.getAppsView().getAppsStore().setApps(mApps, mAppsModelFlags);
        }
    }

    /** Updates the current predictions. */
    public void setPredictedApps(List<ItemInfo> predictedApps) {
        if (!FeatureFlags.ENABLE_ALL_APPS_IN_TASKBAR.get()) {
            return;
        }

        mPredictedApps = predictedApps;
        if (mAllAppsContext != null) {
            mAllAppsContext.getAppsView().getFloatingHeaderView()
                    .findFixedRowByType(PredictionRowView.class)
                    .setPredictedApps(mPredictedApps);
        }
    }

    /** Opens the {@link TaskbarAllAppsContainerView} in a new window. */
    public void show() {
        if (mProxyView.isOpen()) {
            return;
        }
        mProxyView.show();

        mAllAppsContext = new TaskbarAllAppsContext(mTaskbarContext,
                this,
                mControllers.taskbarStashController);
        mAllAppsContext.getDragController().init(mControllers);
        mTaskbarContext.addOnDeviceProfileChangeListener(this);
        Optional.ofNullable(mAllAppsContext.getSystemService(WindowManager.class))
                .ifPresent(m -> m.addView(mAllAppsContext.getDragLayer(), mLayoutParams));

        mAllAppsContext.getAppsView().getAppsStore().setApps(mApps, mAppsModelFlags);
        mAllAppsContext.getAppsView().getFloatingHeaderView()
                .findFixedRowByType(PredictionRowView.class)
                .setPredictedApps(mPredictedApps);
    }

    /** Closes the {@link TaskbarAllAppsContainerView}. */
    public void hide() {
        mProxyView.close(true);
    }

    /**
     * Removes the all apps window from the hierarchy, if all floating views are closed and there is
     * no system drag operation in progress.
     * <p>
     * This method should be called after an exit animation finishes, if applicable.
     */
    void maybeCloseWindow() {
        if (AbstractFloatingView.getOpenView(mAllAppsContext, TYPE_ALL) != null
                || mAllAppsContext.getDragController().isSystemDragInProgress()) {
            return;
        }
        mProxyView.close(false);
        mTaskbarContext.removeOnDeviceProfileChangeListener(this);
        Optional.ofNullable(mAllAppsContext)
                .map(c -> c.getSystemService(WindowManager.class))
                .ifPresent(m -> m.removeView(mAllAppsContext.getDragLayer()));
        mAllAppsContext = null;
    }

    private LayoutParams createLayoutParams() {
        LayoutParams layoutParams = new LayoutParams(
                TYPE_APPLICATION_OVERLAY,
                0,
                PixelFormat.TRANSLUCENT);
        layoutParams.setTitle(WINDOW_TITLE);
        layoutParams.gravity = Gravity.BOTTOM;
        layoutParams.packageName = mTaskbarContext.getPackageName();
        layoutParams.setFitInsetsTypes(0); // Handled by container view.
        layoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layoutParams.setSystemApplicationOverlay(true);
        return layoutParams;
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        Optional.ofNullable(mAllAppsContext).ifPresent(c -> c.updateDeviceProfile(dp));
    }

    /**
     * Proxy view connecting taskbar drag layer to the all apps window.
     * <p>
     * The all apps view is in a separate window and has its own drag layer, but this proxy lets it
     * behave as though its in the taskbar drag layer. For instance, when the taskbar closes all
     * {@link AbstractFloatingView} instances, the all apps window will also close.
     */
    private class TaskbarAllAppsProxyView extends AbstractFloatingView {

        private TaskbarAllAppsProxyView(Context context) {
            super(context, null);
        }

        private void show() {
            mIsOpen = true;
            mTaskbarContext.getDragLayer().addView(this);
        }

        @Override
        protected void handleClose(boolean animate) {
            mTaskbarContext.getDragLayer().removeView(this);
            Optional.ofNullable(mAllAppsContext)
                    .map(TaskbarAllAppsContext::getAllAppsViewController)
                    .ifPresent(v -> v.close(animate));
        }

        @Override
        protected boolean isOfType(int type) {
            return (type & TYPE_TASKBAR_ALL_APPS) != 0;
        }

        @Override
        public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
            return false;
        }
    }
}

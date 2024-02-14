/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.launcher3.taskbar;

import static android.view.HapticFeedbackConstants.LONG_PRESS;
import static android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS;

import static com.android.launcher3.anim.Interpolators.EMPHASIZED;
import static com.android.launcher3.anim.Interpolators.FINAL_FRAME;
import static com.android.launcher3.anim.Interpolators.INSTANT;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.launcher3.config.FeatureFlags.FORCE_PERSISTENT_TASKBAR;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_LONGPRESS_HIDE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TASKBAR_LONGPRESS_SHOW;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TRANSIENT_TASKBAR_HIDE;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_TRANSIENT_TASKBAR_SHOW;
import static com.android.launcher3.taskbar.TaskbarKeyguardController.MASK_ANY_SYSUI_LOCKED;
import static com.android.launcher3.taskbar.TaskbarManager.SYSTEM_ACTION_ID_TASKBAR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.FlagDebugUtils.appendFlag;
import static com.android.launcher3.util.FlagDebugUtils.formatFlagChange;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_GOING_AWAY;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.app.RemoteAction;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.util.Log;
import android.view.InsetsController;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.Interpolator;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.launcher3.Alarm;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.AnimatorListeners;
import com.android.launcher3.testing.shared.TestProtocol;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.quickstep.SystemUiProxy;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.StringJoiner;
import java.util.function.IntPredicate;

/**
 * Coordinates between controllers such as TaskbarViewController and StashedHandleViewController to
 * create a cohesive animation between stashed/unstashed states.
 */
public class TaskbarStashController implements TaskbarControllers.LoggableTaskbarController {
    private static final String TAG = TaskbarStashController.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int FLAG_IN_APP = 1 << 0;
    public static final int FLAG_STASHED_IN_APP_MANUAL = 1 << 1; // long press, persisted
    public static final int FLAG_STASHED_IN_APP_SYSUI = 1 << 2; // shade open, ...
    public static final int FLAG_STASHED_IN_APP_SETUP = 1 << 3; // setup wizard and AllSetActivity
    public static final int FLAG_STASHED_IN_APP_IME = 1 << 4; // IME is visible
    public static final int FLAG_IN_STASHED_LAUNCHER_STATE = 1 << 5;
    public static final int FLAG_STASHED_IN_TASKBAR_ALL_APPS = 1 << 6; // All apps is visible.
    public static final int FLAG_IN_SETUP = 1 << 7; // In the Setup Wizard
    public static final int FLAG_STASHED_SMALL_SCREEN = 1 << 8; // phone screen gesture nav, stashed
    public static final int FLAG_STASHED_IN_APP_AUTO = 1 << 9; // Autohide (transient taskbar).
    public static final int FLAG_STASHED_SYSUI = 1 << 10; //  app pinning,...
    public static final int FLAG_STASHED_DEVICE_LOCKED = 1 << 11; // device is locked: keyguard, ...

    // If any of these flags are enabled, isInApp should return true.
    private static final int FLAGS_IN_APP = FLAG_IN_APP | FLAG_IN_SETUP;

    // If we're in an app and any of these flags are enabled, taskbar should be stashed.
    private static final int FLAGS_STASHED_IN_APP = FLAG_STASHED_IN_APP_MANUAL
            | FLAG_STASHED_IN_APP_SYSUI | FLAG_STASHED_IN_APP_SETUP
            | FLAG_STASHED_IN_APP_IME | FLAG_STASHED_IN_TASKBAR_ALL_APPS
            | FLAG_STASHED_SMALL_SCREEN | FLAG_STASHED_IN_APP_AUTO;

    private static final int FLAGS_STASHED_IN_APP_IGNORING_IME =
            FLAGS_STASHED_IN_APP & ~FLAG_STASHED_IN_APP_IME;

    // If any of these flags are enabled, inset apps by our stashed height instead of our unstashed
    // height. This way the reported insets are consistent even during transitions out of the app.
    // Currently any flag that causes us to stash in an app is included, except for IME or All Apps
    // since those cover the underlying app anyway and thus the app shouldn't change insets.
    private static final int FLAGS_REPORT_STASHED_INSETS_TO_APP = FLAGS_STASHED_IN_APP
            & ~FLAG_STASHED_IN_APP_IME & ~FLAG_STASHED_IN_TASKBAR_ALL_APPS;

    // If any of these flags are enabled, the taskbar must be stashed.
    private static final int FLAGS_FORCE_STASHED = FLAG_STASHED_SYSUI | FLAG_STASHED_DEVICE_LOCKED
            | FLAG_STASHED_IN_TASKBAR_ALL_APPS | FLAG_STASHED_SMALL_SCREEN;

    /**
     * How long to stash/unstash when manually invoked via long press.
     *
     * Use {@link #getStashDuration()} to query duration
     */
    private static final long TASKBAR_STASH_DURATION =
            InsetsController.ANIMATION_DURATION_RESIZE;

    /**
     * How long to stash/unstash transient taskbar.
     *
     * Use {@link #getStashDuration()} to query duration.
     */
    private static final long TRANSIENT_TASKBAR_STASH_DURATION = 417;

    /**
     * How long to stash/unstash when keyboard is appearing/disappearing.
     */
    private static final long TASKBAR_STASH_DURATION_FOR_IME = 80;

    /**
     * The scale TaskbarView animates to when being stashed.
     */
    protected static final float STASHED_TASKBAR_SCALE = 0.5f;

    /**
     * How long the hint animation plays, starting on motion down.
     */
    private static final long TASKBAR_HINT_STASH_DURATION =
            ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT;

    /**
     * How long to delay the icon/stash handle alpha.
     */
    private static final long TASKBAR_STASH_ALPHA_START_DELAY = 33;

    /**
     * How long the icon/stash handle alpha animation plays.
     */
    private static final long TASKBAR_STASH_ALPHA_DURATION = 50;

    /**
     * How long to delay the icon/stash handle alpha for the home to app taskbar animation.
     */
    private static final long TASKBAR_STASH_ICON_ALPHA_HOME_TO_APP_START_DELAY = 66;

    /**
     * The scale that TaskbarView animates to when hinting towards the stashed state.
     */
    private static final float STASHED_TASKBAR_HINT_SCALE = 0.9f;

    /**
     * The scale that the stashed handle animates to when hinting towards the unstashed state.
     */
    private static final float UNSTASHED_TASKBAR_HANDLE_HINT_SCALE = 1.1f;

    /**
     * The SharedPreferences key for whether user has manually stashed the taskbar.
     */
    private static final String SHARED_PREFS_STASHED_KEY = "taskbar_is_stashed";

    /**
     * Whether taskbar should be stashed out of the box.
     */
    private static final boolean DEFAULT_STASHED_PREF = false;

    // Auto stashes when user has not interacted with the Taskbar after X ms.
    private static final long NO_TOUCH_TIMEOUT_TO_STASH_MS = 5000;

    // Duration for which an unlock event is considered "current", as other events are received
    // asynchronously.
    private static final long UNLOCK_TRANSITION_MEMOIZATION_MS = 200;

    /**
     * The default stash animation, morphing the taskbar into the navbar.
     */
    private static final int TRANSITION_DEFAULT = 0;
    /**
     * Transitioning from launcher to app. Same as TRANSITION_DEFAULT, differs in internal
     * animation timings.
     */
    private static final int TRANSITION_HOME_TO_APP = 1;
    /**
     * Fading the navbar in and out, where the taskbar jumpcuts in and out at the very begin/end of
     * the transition. Used to transition between the hotseat and navbar` without the stash/unstash
     * transition.
     */
    private static final int TRANSITION_HANDLE_FADE = 2;
    /**
     * Same as TRANSITION_DEFAULT, but exclusively used during an "navbar unstash to hotseat
     * animation" bound to the progress of a swipe gesture. It differs from TRANSITION_DEFAULT
     * by not scaling the height of the taskbar background.
     */
    private static final int TRANSITION_UNSTASH_SUW_MANUAL = 3;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            TRANSITION_DEFAULT,
            TRANSITION_HOME_TO_APP,
            TRANSITION_HANDLE_FADE,
            TRANSITION_UNSTASH_SUW_MANUAL,
    })
    private @interface StashAnimation {}

    private final TaskbarActivityContext mActivity;
    private final SharedPreferences mPrefs;
    private final int mStashedHeight;
    private final int mUnstashedHeight;
    private final SystemUiProxy mSystemUiProxy;

    // Initialized in init.
    private TaskbarControllers mControllers;
    // Taskbar background properties.
    private AnimatedFloat mTaskbarBackgroundOffset;
    private AnimatedFloat mTaskbarImeBgAlpha;
    // TaskbarView icon properties.
    private MultiProperty mIconAlphaForStash;
    private AnimatedFloat mIconScaleForStash;
    private AnimatedFloat mIconTranslationYForStash;
    // Stashed handle properties.
    private MultiProperty mTaskbarStashedHandleAlpha;
    private AnimatedFloat mTaskbarStashedHandleHintScale;
    private final AccessibilityManager mAccessibilityManager;

    /** Whether we are currently visually stashed (might change based on launcher state). */
    private boolean mIsStashed = false;
    private int mState;

    private @Nullable AnimatorSet mAnimator;
    private boolean mIsSystemGestureInProgress;
    private boolean mIsImeShowing;
    private boolean mIsImeSwitcherShowing;

    private boolean mEnableManualStashingDuringTests = false;

    private final Alarm mTimeoutAlarm = new Alarm();
    private boolean mEnableBlockingTimeoutDuringTests = false;

    // Evaluate whether the handle should be stashed
    private final StatePropertyHolder mStatePropertyHolder = new StatePropertyHolder(
            flags -> {
                boolean inApp = hasAnyFlag(flags, FLAGS_IN_APP);
                boolean stashedInApp = hasAnyFlag(flags, FLAGS_STASHED_IN_APP);
                boolean stashedLauncherState = hasAnyFlag(flags, FLAG_IN_STASHED_LAUNCHER_STATE);
                boolean forceStashed = hasAnyFlag(flags, FLAGS_FORCE_STASHED);
                return (inApp && stashedInApp) || (!inApp && stashedLauncherState) || forceStashed;
            });

    private boolean mIsTaskbarSystemActionRegistered = false;
    private TaskbarSharedState mTaskbarSharedState;

    public TaskbarStashController(TaskbarActivityContext activity) {
        mActivity = activity;
        mPrefs = LauncherPrefs.getPrefs(mActivity);
        mSystemUiProxy = SystemUiProxy.INSTANCE.get(activity);
        mAccessibilityManager = mActivity.getSystemService(AccessibilityManager.class);

        mUnstashedHeight = mActivity.getDeviceProfile().taskbarHeight;
        mStashedHeight = mActivity.getDeviceProfile().stashedTaskbarHeight;
    }

    /**
     * Show Taskbar upon receiving broadcast
     */
    public void showTaskbarFromBroadcast() {
        // If user is in middle of taskbar education handle go to next step of education
        if (mControllers.taskbarEduTooltipController.isBeforeTooltipFeaturesStep()) {
            mControllers.taskbarEduTooltipController.hide();
            mControllers.taskbarEduTooltipController.maybeShowFeaturesEdu();
        }
        updateAndAnimateTransientTaskbar(false);
    }

    /**
     * Initializes the controller
     */
    public void init(
            TaskbarControllers controllers,
            boolean setupUIVisible,
            TaskbarSharedState sharedState) {
        mControllers = controllers;
        mTaskbarSharedState = sharedState;

        TaskbarDragLayerController dragLayerController = controllers.taskbarDragLayerController;
        mTaskbarBackgroundOffset = dragLayerController.getTaskbarBackgroundOffset();
        mTaskbarImeBgAlpha = dragLayerController.getImeBgTaskbar();

        TaskbarViewController taskbarViewController = controllers.taskbarViewController;
        mIconAlphaForStash = taskbarViewController.getTaskbarIconAlpha().get(
                TaskbarViewController.ALPHA_INDEX_STASH);
        mIconScaleForStash = taskbarViewController.getTaskbarIconScaleForStash();
        mIconTranslationYForStash = taskbarViewController.getTaskbarIconTranslationYForStash();

        StashedHandleViewController stashedHandleController =
                controllers.stashedHandleViewController;
        mTaskbarStashedHandleAlpha = stashedHandleController.getStashedHandleAlpha().get(
                StashedHandleViewController.ALPHA_INDEX_STASHED);
        mTaskbarStashedHandleHintScale = stashedHandleController.getStashedHandleHintScale();

        boolean isTransientTaskbar = DisplayController.isTransientTaskbar(mActivity);
        // We use supportsVisualStashing() here instead of supportsManualStashing() because we want
        // it to work properly for tests that recreate taskbar. This check is here just to ensure
        // that taskbar unstashes when going to 3 button mode (supportsVisualStashing() false).
        boolean isManuallyStashedInApp = supportsVisualStashing()
                && !isTransientTaskbar
                && !FORCE_PERSISTENT_TASKBAR.get()
                && mPrefs.getBoolean(SHARED_PREFS_STASHED_KEY, DEFAULT_STASHED_PREF);
        boolean isInSetup = !mActivity.isUserSetupComplete() || setupUIVisible;
        updateStateForFlag(FLAG_STASHED_IN_APP_MANUAL, isManuallyStashedInApp);
        updateStateForFlag(FLAG_STASHED_IN_APP_AUTO, isTransientTaskbar);
        updateStateForFlag(FLAG_STASHED_IN_APP_SETUP, isInSetup);
        updateStateForFlag(FLAG_IN_SETUP, isInSetup);
        updateStateForFlag(FLAG_STASHED_SMALL_SCREEN, isPhoneMode()
                && !mActivity.isThreeButtonNav());
        // For now, assume we're in an app, since LauncherTaskbarUIController won't be able to tell
        // us that we're paused until a bit later. This avoids flickering upon recreating taskbar.
        updateStateForFlag(FLAG_IN_APP, true);
        applyState(/* duration = */ 0);

        notifyStashChange(/* visible */ false, /* stashed */ isStashedInApp());
    }

    /**
     * Returns whether the taskbar can visually stash into a handle based on the current device
     * state.
     */
    public boolean supportsVisualStashing() {
        return !mActivity.isThreeButtonNav() && mControllers.uiController.supportsVisualStashing();
    }

    /**
     * Returns whether the user can manually stash the taskbar based on the current device state.
     */
    protected boolean supportsManualStashing() {
        if (FORCE_PERSISTENT_TASKBAR.get()) {
            return false;
        }
        return supportsVisualStashing()
                && isInApp()
                && (!Utilities.isRunningInTestHarness() || mEnableManualStashingDuringTests)
                && !DisplayController.isTransientTaskbar(mActivity);
    }

    /**
     * Enables support for manual stashing. This should only be used to add this functionality
     * to Launcher specific tests.
     */
    @VisibleForTesting
    public void enableManualStashingDuringTests(boolean enableManualStashing) {
        mEnableManualStashingDuringTests = enableManualStashing;
    }

    /**
     * Enables the auto timeout for taskbar stashing. This method should only be used for taskbar
     * testing.
     */
    @VisibleForTesting
    public void enableBlockingTimeoutDuringTests(boolean enableBlockingTimeout) {
        mEnableBlockingTimeoutDuringTests = enableBlockingTimeout;
    }

    /**
     * Sets the flag indicating setup UI is visible
     */
    protected void setSetupUIVisible(boolean isVisible) {
        boolean hideTaskbar = isVisible || !mActivity.isUserSetupComplete();
        updateStateForFlag(FLAG_IN_SETUP, hideTaskbar);
        updateStateForFlag(FLAG_STASHED_IN_APP_SETUP, hideTaskbar);
        applyState(hideTaskbar ? 0 : getStashDuration());
    }

    /**
     * Returns how long the stash/unstash animation should play.
     */
    public long getStashDuration() {
        return DisplayController.isTransientTaskbar(mActivity)
                ? TRANSIENT_TASKBAR_STASH_DURATION
                : TASKBAR_STASH_DURATION;
    }

    /**
     * Returns whether the taskbar is currently visually stashed.
     */
    public boolean isStashed() {
        return mIsStashed;
    }

    /**
     * Returns whether the taskbar should be stashed in apps (e.g. user long pressed to stash).
     */
    public boolean isStashedInApp() {
        return hasAnyFlag(FLAGS_STASHED_IN_APP);
    }

    /**
     * Returns whether the taskbar should be stashed in apps regardless of the IME visibility.
     */
    public boolean isStashedInAppIgnoringIme() {
        return hasAnyFlag(FLAGS_STASHED_IN_APP_IGNORING_IME);
    }

    /**
     * Returns whether the taskbar should be stashed in the current LauncherState.
     */
    public boolean isInStashedLauncherState() {
        return (hasAnyFlag(FLAG_IN_STASHED_LAUNCHER_STATE) && supportsVisualStashing());
    }

    /**
     * @return {@code true} if we're not on a large screen AND using gesture nav
     */
    private boolean isPhoneMode() {
        return TaskbarManager.isPhoneMode(mActivity.getDeviceProfile());
    }

    private boolean hasAnyFlag(int flagMask) {
        return hasAnyFlag(mState, flagMask);
    }

    private boolean hasAnyFlag(int flags, int flagMask) {
        return (flags & flagMask) != 0;
    }


    /**
     * Returns whether the taskbar is currently visible and not in the process of being stashed.
     */
    public boolean isTaskbarVisibleAndNotStashing() {
        return !mIsStashed && mControllers.taskbarViewController.areIconsVisible();
    }

    public boolean isInApp() {
        return hasAnyFlag(FLAGS_IN_APP);
    }

    /**
     * Returns the height that taskbar will be touchable.
     */
    public int getTouchableHeight() {
        return mIsStashed
                ? mStashedHeight
                : (mUnstashedHeight + mActivity.getDeviceProfile().taskbarBottomMargin);
    }

    /**
     * Returns the height that taskbar will inset when inside apps.
     * @see android.view.WindowInsets.Type#navigationBars()
     * @see android.view.WindowInsets.Type#systemBars()
     */
    public int getContentHeightToReportToApps() {
        if ((isPhoneMode() && !mActivity.isThreeButtonNav())
                || DisplayController.isTransientTaskbar(mActivity)) {
            return getStashedHeight();
        }

        if (supportsVisualStashing() && hasAnyFlag(FLAGS_REPORT_STASHED_INSETS_TO_APP)) {
            DeviceProfile dp = mActivity.getDeviceProfile();
            if (hasAnyFlag(FLAG_STASHED_IN_APP_SETUP) && dp.isTaskbarPresent) {
                // We always show the back button in SUW but in portrait the SUW layout may not
                // be wide enough to support overlapping the nav bar with its content.
                // We're sending different res values in portrait vs landscape
                return mActivity.getResources().getDimensionPixelSize(R.dimen.taskbar_suw_insets);
            }
            boolean isAnimating = mAnimator != null && mAnimator.isStarted();
            if (!mControllers.stashedHandleViewController.isStashedHandleVisible()
                    && isInApp()
                    && !isAnimating) {
                // We are in a settled state where we're not showing the handle even though taskbar
                // is stashed. This can happen for example when home button is disabled (see
                // StashedHandleViewController#setIsHomeButtonDisabled()).
                return 0;
            }
            return mStashedHeight;
        }

        return mUnstashedHeight;
    }

    /**
     * Returns the height that taskbar will inset when inside apps.
     * @see android.view.WindowInsets.Type#tappableElement()
     */
    public int getTappableHeightToReportToApps() {
        int contentHeight = getContentHeightToReportToApps();
        return contentHeight <= mStashedHeight ? 0 : contentHeight;
    }

    public int getStashedHeight() {
        return mStashedHeight;
    }

    /**
     * Stash or unstashes the transient taskbar, using the default TASKBAR_STASH_DURATION.
     */
    public void updateAndAnimateTransientTaskbar(boolean stash) {
        updateAndAnimateTransientTaskbar(stash, TASKBAR_STASH_DURATION);
    }

    /**
     * Stash or unstashes the transient taskbar.
     */
    public void updateAndAnimateTransientTaskbar(boolean stash, long duration) {
        if (!DisplayController.isTransientTaskbar(mActivity)) {
            return;
        }

        if (stash && mControllers.taskbarAutohideSuspendController.isSuspended()
                && !mControllers.taskbarAutohideSuspendController
                .isSuspendedForTransientTaskbarInOverview()) {
            // Avoid stashing if autohide is currently suspended.
            return;
        }

        if (hasAnyFlag(FLAG_STASHED_IN_APP_AUTO) != stash) {
            updateStateForFlag(FLAG_STASHED_IN_APP_AUTO, stash);
            applyState();
        }
    }

    /**
     * Should be called when long pressing the nav region when taskbar is present.
     * @return Whether taskbar was stashed and now is unstashed.
     */
    public boolean onLongPressToUnstashTaskbar() {
        if (!isStashed()) {
            // We only listen for long press on the nav region to unstash the taskbar. To stash the
            // taskbar, we use an OnLongClickListener on TaskbarView instead.
            return false;
        }
        if (!canCurrentlyManuallyUnstash()) {
            return false;
        }
        if (updateAndAnimateIsManuallyStashedInApp(false)) {
            mControllers.taskbarActivityContext.getDragLayer().performHapticFeedback(LONG_PRESS);
            return true;
        }
        return false;
    }

    /**
     * Returns whether taskbar will unstash when long pressing it based on the current state. The
     * only time this is true is if the user is in an app and the taskbar is only stashed because
     * the user previously long pressed to manually stash (not due to other reasons like IME).
     */
    private boolean canCurrentlyManuallyUnstash() {
        return (mState & (FLAG_IN_APP | FLAGS_STASHED_IN_APP))
                == (FLAG_IN_APP | FLAG_STASHED_IN_APP_MANUAL);
    }

    /**
     * Updates whether we should stash the taskbar when in apps, and animates to the changed state.
     * @return Whether we started an animation to either be newly stashed or unstashed.
     */
    public boolean updateAndAnimateIsManuallyStashedInApp(boolean isManuallyStashedInApp) {
        if (!supportsManualStashing()) {
            return false;
        }
        if (hasAnyFlag(FLAG_STASHED_IN_APP_MANUAL) != isManuallyStashedInApp) {
            mPrefs.edit().putBoolean(SHARED_PREFS_STASHED_KEY, isManuallyStashedInApp).apply();
            updateStateForFlag(FLAG_STASHED_IN_APP_MANUAL, isManuallyStashedInApp);
            applyState();
            return true;
        }
        return false;
    }

    /**
     * Adds the Taskbar unstash to Hotseat animator to the animator set.
     *
     * This should be used to run a Taskbar unstash to Hotseat animation whose progress matches a
     * swipe progress.
     *
     * @param placeholderDuration a placeholder duration to be used to ensure all full-length
     *                            sub-animations are properly coordinated. This duration should not
     *                            actually be used since this animation tracks a swipe progress.
     */
    protected void addUnstashToHotseatAnimation(AnimatorSet animation, int placeholderDuration) {
        createAnimToIsStashed(
                /* isStashed= */ false,
                placeholderDuration,
                TRANSITION_UNSTASH_SUW_MANUAL);
        animation.play(mAnimator);
    }

    /**
     * Create a stash animation and save to {@link #mAnimator}.
     * @param isStashed whether it's a stash animation or an unstash animation
     * @param duration duration of the animation
     * @param animationType what transition type to play.
     */
    private void createAnimToIsStashed(boolean isStashed, long duration,
            @StashAnimation int animationType) {
        if (animationType == TRANSITION_UNSTASH_SUW_MANUAL && isStashed) {
            // The STASH_ANIMATION_SUW_MANUAL must only be used during an unstash animation.
            Log.e(TAG, "Illegal arguments:Using TRANSITION_UNSTASH_SUW_MANUAL to stash taskbar");
        }

        if (mAnimator != null) {
            mAnimator.cancel();
        }
        mAnimator = new AnimatorSet();
        addJankMonitorListener(mAnimator, /* appearing= */ !mIsStashed);
        boolean isTransientTaskbar = DisplayController.isTransientTaskbar(mActivity);
        final float stashTranslation = isPhoneMode() || isTransientTaskbar
                ? 0
                : (mUnstashedHeight - mStashedHeight);

        if (!supportsVisualStashing()) {
            // Just hide/show the icons and background instead of stashing into a handle.
            mAnimator.play(mIconAlphaForStash.animateToValue(isStashed ? 0 : 1)
                    .setDuration(duration));
            mAnimator.playTogether(mTaskbarBackgroundOffset.animateToValue(isStashed ? 1 : 0)
                    .setDuration(duration));
            mAnimator.playTogether(mIconTranslationYForStash.animateToValue(isStashed
                            ? stashTranslation : 0)
                    .setDuration(duration));
            mAnimator.play(mTaskbarImeBgAlpha.animateToValue(
                    hasAnyFlag(FLAG_STASHED_IN_APP_IME) ? 0 : 1).setDuration(duration));
            mAnimator.addListener(AnimatorListeners.forEndCallback(() -> mAnimator = null));
            return;
        }

        if (isTransientTaskbar) {
            createTransientAnimToIsStashed(mAnimator, isStashed, duration, animationType);
        } else {
            createAnimToIsStashed(mAnimator, isStashed, duration, stashTranslation, animationType);
        }

        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mIsStashed = isStashed;
                onIsStashedChanged(mIsStashed);

                cancelTimeoutIfExists();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;

                if (!mIsStashed) {
                    tryStartTaskbarTimeout();
                }

                // only announce if we are actually animating
                if (duration > 0 && isInApp()) {
                    mControllers.taskbarViewController.announceForAccessibility();
                }
            }
        });
    }

    private void createAnimToIsStashed(AnimatorSet as, boolean isStashed, long duration,
            float stashTranslation, @StashAnimation int animationType) {
        AnimatorSet fullLengthAnimatorSet = new AnimatorSet();
        // Not exactly half and may overlap. See [first|second]HalfDurationScale below.
        AnimatorSet firstHalfAnimatorSet = new AnimatorSet();
        AnimatorSet secondHalfAnimatorSet = new AnimatorSet();

        final float firstHalfDurationScale;
        final float secondHalfDurationScale;

        if (isStashed) {
            firstHalfDurationScale = 0.75f;
            secondHalfDurationScale = 0.5f;

            fullLengthAnimatorSet.play(mIconTranslationYForStash.animateToValue(stashTranslation));
            fullLengthAnimatorSet.play(mTaskbarBackgroundOffset.animateToValue(1));

            firstHalfAnimatorSet.playTogether(
                    mIconAlphaForStash.animateToValue(0),
                    mIconScaleForStash.animateToValue(isPhoneMode() ?
                            0 : STASHED_TASKBAR_SCALE)
            );
            secondHalfAnimatorSet.playTogether(
                    mTaskbarStashedHandleAlpha.animateToValue(1)
            );

            if (animationType == TRANSITION_HANDLE_FADE) {
                fullLengthAnimatorSet.setInterpolator(INSTANT);
                firstHalfAnimatorSet.setInterpolator(INSTANT);
            }
        } else  {
            firstHalfDurationScale = 0.5f;
            secondHalfDurationScale = 0.75f;

            fullLengthAnimatorSet.playTogether(
                    mIconScaleForStash.animateToValue(1),
                    mIconTranslationYForStash.animateToValue(0));

            final boolean animateBg = animationType != TRANSITION_UNSTASH_SUW_MANUAL;
            if (animateBg) {
                fullLengthAnimatorSet.play(mTaskbarBackgroundOffset.animateToValue(0));
            } else {
                fullLengthAnimatorSet.addListener(AnimatorListeners.forEndCallback(
                        () -> mTaskbarBackgroundOffset.updateValue(0)));
            }

            firstHalfAnimatorSet.playTogether(
                    mTaskbarStashedHandleAlpha.animateToValue(0)
            );
            secondHalfAnimatorSet.playTogether(
                    mIconAlphaForStash.animateToValue(1)
            );

            if (animationType == TRANSITION_HANDLE_FADE) {
                fullLengthAnimatorSet.setInterpolator(FINAL_FRAME);
                secondHalfAnimatorSet.setInterpolator(FINAL_FRAME);
            }
        }

        fullLengthAnimatorSet.play(mControllers.stashedHandleViewController
                .createRevealAnimToIsStashed(isStashed));
        // Return the stashed handle to its default scale in case it was changed as part of the
        // feedforward hint. Note that the reveal animation above also visually scales it.
        fullLengthAnimatorSet.play(mTaskbarStashedHandleHintScale.animateToValue(1f));

        fullLengthAnimatorSet.setDuration(duration);
        firstHalfAnimatorSet.setDuration((long) (duration * firstHalfDurationScale));
        secondHalfAnimatorSet.setDuration((long) (duration * secondHalfDurationScale));
        secondHalfAnimatorSet.setStartDelay((long) (duration * (1 - secondHalfDurationScale)));

        as.playTogether(fullLengthAnimatorSet, firstHalfAnimatorSet,
                secondHalfAnimatorSet);

    }

    private void createTransientAnimToIsStashed(AnimatorSet as, boolean isStashed, long duration,
            @StashAnimation int animationType) {
        // Target values of the properties this is going to set
        final float backgroundOffsetTarget = isStashed ? 1 : 0;
        final float iconAlphaTarget = isStashed ? 0 : 1;
        final float stashedHandleAlphaTarget = isStashed ? 1 : 0;

        // Timing for the alpha values depend on the animation played
        long iconAlphaStartDelay = 0, iconAlphaDuration = 0, stashedHandleAlphaDelay = 0,
                stashedHandleAlphaDuration = 0;
        if (duration > 0) {
            if (animationType == TRANSITION_HANDLE_FADE) {
                // When fading, the handle fades in/out at the beginning of the transition with
                // TASKBAR_STASH_ALPHA_DURATION.
                stashedHandleAlphaDuration = TASKBAR_STASH_ALPHA_DURATION;
                // The iconAlphaDuration must be set to duration for the skippable interpolators
                // below to work.
                iconAlphaDuration = duration;
            } else {
                iconAlphaStartDelay = TASKBAR_STASH_ALPHA_START_DELAY;
                iconAlphaDuration = TASKBAR_STASH_ALPHA_DURATION;
                stashedHandleAlphaDuration = TASKBAR_STASH_ALPHA_DURATION;

                if (isStashed) {
                    if (animationType == TRANSITION_HOME_TO_APP) {
                        iconAlphaStartDelay = TASKBAR_STASH_ICON_ALPHA_HOME_TO_APP_START_DELAY;
                    }
                    stashedHandleAlphaDelay = iconAlphaStartDelay;
                    stashedHandleAlphaDuration = Math.max(0, duration - iconAlphaStartDelay);
                }

            }
        }

        play(as, mTaskbarStashedHandleAlpha.animateToValue(stashedHandleAlphaTarget),
                stashedHandleAlphaDelay,
                stashedHandleAlphaDuration, LINEAR);

        // The rest of the animations might be "skipped" in TRANSITION_HANDLE_FADE transitions.
        AnimatorSet skippable = as;
        if (animationType == TRANSITION_HANDLE_FADE) {
            skippable = new AnimatorSet();
            as.play(skippable);
            skippable.setInterpolator(isStashed ? INSTANT : FINAL_FRAME);
        }

        final boolean animateBg = animationType != TRANSITION_UNSTASH_SUW_MANUAL;
        if (animateBg) {
            play(skippable, mTaskbarBackgroundOffset.animateToValue(backgroundOffsetTarget), 0,
                    duration, EMPHASIZED);
        } else {
            skippable.addListener(AnimatorListeners.forEndCallback(
                    () -> mTaskbarBackgroundOffset.updateValue(backgroundOffsetTarget)));
        }

        play(skippable, mIconAlphaForStash.animateToValue(iconAlphaTarget), iconAlphaStartDelay,
                iconAlphaDuration,
                LINEAR);

        if (isStashed) {
            play(skippable, mControllers.taskbarSpringOnStashController.createSpringToStash(),
                    0, duration, LINEAR);
        }

        mControllers.taskbarViewController.addRevealAnimToIsStashed(skippable, isStashed, duration,
                EMPHASIZED);

        play(skippable, mControllers.stashedHandleViewController
                .createRevealAnimToIsStashed(isStashed), 0, duration, EMPHASIZED);

        // Return the stashed handle to its default scale in case it was changed as part of the
        // feedforward hint. Note that the reveal animation above also visually scales it.
        skippable.play(mTaskbarStashedHandleHintScale.animateToValue(1f)
                .setDuration(isStashed ? duration / 2 : duration));
    }

    private static void play(AnimatorSet as, Animator a, long startDelay, long duration,
            Interpolator interpolator) {
        a.setDuration(duration);
        a.setStartDelay(startDelay);
        a.setInterpolator(interpolator);
        as.play(a);
    }

    private void addJankMonitorListener(AnimatorSet animator, boolean expanding) {
        View v = mControllers.taskbarActivityContext.getDragLayer();
        int action = expanding ? InteractionJankMonitor.CUJ_TASKBAR_EXPAND :
                InteractionJankMonitor.CUJ_TASKBAR_COLLAPSE;
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(@NonNull Animator animation) {
                InteractionJankMonitor.getInstance().begin(v, action);
            }

            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                InteractionJankMonitor.getInstance().end(action);
            }
        });
    }
    /**
     * Creates and starts a partial stash animation, hinting at the new state that will trigger when
     * long press is detected.
     * @param animateForward Whether we are going towards the new stashed state or returning to the
     *                       unstashed state.
     */
    public void startStashHint(boolean animateForward) {
        if (isStashed() || !supportsManualStashing()) {
            // Already stashed, no need to hint in that direction.
            return;
        }
        mIconScaleForStash.animateToValue(
                animateForward ? STASHED_TASKBAR_HINT_SCALE : 1)
                .setDuration(TASKBAR_HINT_STASH_DURATION).start();
    }

    /**
     * Creates and starts a partial unstash animation, hinting at the new state that will trigger
     * when long press is detected.
     * @param animateForward Whether we are going towards the new unstashed state or returning to
     *                       the stashed state.
     */
    public void startUnstashHint(boolean animateForward) {
        if (!isStashed()) {
            // Already unstashed, no need to hint in that direction.
            return;
        }
        if (!canCurrentlyManuallyUnstash()) {
            // If any other flags are causing us to be stashed, long press won't cause us to
            // unstash, so don't hint that it will.
            return;
        }
        mTaskbarStashedHandleHintScale.animateToValue(
                animateForward ? UNSTASHED_TASKBAR_HANDLE_HINT_SCALE : 1)
                .setDuration(TASKBAR_HINT_STASH_DURATION).start();
    }

    private void onIsStashedChanged(boolean isStashed) {
        mControllers.runAfterInit(() -> {
            mControllers.stashedHandleViewController.onIsStashedChanged(isStashed);
            mControllers.taskbarInsetsController.onTaskbarWindowHeightOrInsetsChanged();
        });
    }

    public void applyState() {
        applyState(hasAnyFlag(FLAG_IN_SETUP) ? 0 : TASKBAR_STASH_DURATION);
    }

    public void applyState(long duration) {
        Animator animator = createApplyStateAnimator(duration);
        if (animator != null) {
            animator.start();
        }
    }

    public void applyState(long duration, long startDelay) {
        Animator animator = createApplyStateAnimator(duration);
        if (animator != null) {
            animator.setStartDelay(startDelay);
            animator.start();
        }
    }

    /**
     * Returns an animator which applies the latest state if mIsStashed is changed, or {@code null}
     * otherwise.
     */
    @Nullable
    public Animator createApplyStateAnimator(long duration) {
        return mStatePropertyHolder.createSetStateAnimator(mState, duration);
    }

    /**
     * Should be called when a system gesture starts and settles, so we can defer updating
     * FLAG_STASHED_IN_APP_IME until after the gesture transition completes.
     */
    public void setSystemGestureInProgress(boolean inProgress) {
        mIsSystemGestureInProgress = inProgress;
        if (mIsSystemGestureInProgress) {
            return;
        }

        // Only update the following flags when system gesture is not in progress.
        boolean shouldStashForIme = shouldStashForIme();
        updateStateForFlag(FLAG_STASHED_IN_TASKBAR_ALL_APPS, false);
        if (hasAnyFlag(FLAG_STASHED_IN_APP_IME) != shouldStashForIme) {
            updateStateForFlag(FLAG_STASHED_IN_APP_IME, shouldStashForIme);
            applyState(TASKBAR_STASH_DURATION_FOR_IME, getTaskbarStashStartDelayForIme());
        } else {
            applyState(mControllers.taskbarOverlayController.getCloseDuration());
        }
    }

    /**
     * Resets the flag if no system gesture is in progress.
     * <p>
     * Otherwise, the reset should be deferred until after the gesture is finished.
     *
     * @see #setSystemGestureInProgress
     */
    public void resetFlagIfNoGestureInProgress(int flag) {
        if (!mIsSystemGestureInProgress) {
            updateStateForFlag(flag, false);
            applyState(mControllers.taskbarOverlayController.getCloseDuration());
        }
    }

    /**
     * When hiding the IME, delay the unstash animation to align with the end of the transition.
     */
    private long getTaskbarStashStartDelayForIme() {
        if (mIsImeShowing) {
            // Only delay when IME is exiting, not entering.
            return 0;
        }
        // This duration is based on input_method_extract_exit.xml.
        long imeExitDuration = mControllers.taskbarActivityContext.getResources()
                .getInteger(android.R.integer.config_shortAnimTime);
        return imeExitDuration - TASKBAR_STASH_DURATION_FOR_IME;
    }

    /** Called when some system ui state has changed. (See SYSUI_STATE_... in QuickstepContract) */
    public void updateStateForSysuiFlags(int systemUiStateFlags, boolean skipAnim) {
        long animDuration = TASKBAR_STASH_DURATION;
        long startDelay = 0;

        updateStateForFlag(FLAG_STASHED_IN_APP_SYSUI, hasAnyFlag(systemUiStateFlags,
                SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE));
        updateStateForFlag(FLAG_STASHED_SYSUI,
                hasAnyFlag(systemUiStateFlags, SYSUI_STATE_SCREEN_PINNING));

        boolean isLocked = hasAnyFlag(systemUiStateFlags, MASK_ANY_SYSUI_LOCKED)
                && !hasAnyFlag(systemUiStateFlags, SYSUI_STATE_STATUS_BAR_KEYGUARD_GOING_AWAY);
        updateStateForFlag(FLAG_STASHED_DEVICE_LOCKED, isLocked);

        // Only update FLAG_STASHED_IN_APP_IME when system gesture is not in progress.
        mIsImeShowing = hasAnyFlag(systemUiStateFlags, SYSUI_STATE_IME_SHOWING);
        mIsImeSwitcherShowing = hasAnyFlag(systemUiStateFlags, SYSUI_STATE_IME_SWITCHER_SHOWING);

        if (!mIsSystemGestureInProgress) {
            updateStateForFlag(FLAG_STASHED_IN_APP_IME, shouldStashForIme());
            animDuration = TASKBAR_STASH_DURATION_FOR_IME;
            startDelay = getTaskbarStashStartDelayForIme();
        }

        applyState(skipAnim ? 0 : animDuration, skipAnim ? 0 : startDelay);
    }

    /**
     * We stash when IME or IME switcher is showing AND NOT
     *  * in small screen AND
     *  * 3 button nav AND
     *  * landscape (or seascape)
     * We do not stash if taskbar is transient
     */
    private boolean shouldStashForIme() {
        if (DisplayController.isTransientTaskbar(mActivity)) {
            return false;
        }
        return (mIsImeShowing || mIsImeSwitcherShowing) &&
                !(isPhoneMode() && mActivity.isThreeButtonNav()
                        && mActivity.getDeviceProfile().isLandscape);
    }

    /**
     * Updates the proper flag to indicate whether the task bar should be stashed.
     *
     * Note that this only updates the flag. {@link #applyState()} needs to be called separately.
     *
     * @param flag The flag to update.
     * @param enabled Whether to enable the flag: True will cause the task bar to be stashed /
     *                unstashed.
     */
    public void updateStateForFlag(int flag, boolean enabled) {
        if (flag == FLAG_IN_APP && TestProtocol.sDebugTracing) {
            Log.d(TestProtocol.TASKBAR_IN_APP_STATE, String.format(
                    "setting flag FLAG_IN_APP to: %b", enabled), new Exception());
        }
        if (enabled) {
            mState |= flag;
        } else {
            mState &= ~flag;
        }
    }

    /**
     * Called after updateStateForFlag() and applyState() have been called.
     * @param changedFlags The flags that have changed.
     */
    private void onStateChangeApplied(int changedFlags) {
        if (hasAnyFlag(changedFlags, FLAGS_STASHED_IN_APP)) {
            mControllers.uiController.onStashedInAppChanged();
        }
        if (hasAnyFlag(changedFlags, FLAGS_STASHED_IN_APP | FLAGS_IN_APP)) {
            notifyStashChange(/* visible */ hasAnyFlag(FLAGS_IN_APP),
                            /* stashed */ isStashedInApp());
            mControllers.taskbarAutohideSuspendController.updateFlag(
                    TaskbarAutohideSuspendController.FLAG_AUTOHIDE_SUSPEND_IN_LAUNCHER, !isInApp());
        }
        if (hasAnyFlag(changedFlags, FLAG_STASHED_IN_APP_MANUAL)) {
            if (hasAnyFlag(FLAG_STASHED_IN_APP_MANUAL)) {
                mActivity.getStatsLogManager().logger().log(LAUNCHER_TASKBAR_LONGPRESS_HIDE);
            } else {
                mActivity.getStatsLogManager().logger().log(LAUNCHER_TASKBAR_LONGPRESS_SHOW);
            }
        }
        if (hasAnyFlag(changedFlags, FLAG_STASHED_IN_APP_AUTO)) {
            mActivity.getStatsLogManager().logger().log(hasAnyFlag(FLAG_STASHED_IN_APP_AUTO)
                    ? LAUNCHER_TRANSIENT_TASKBAR_HIDE
                    : LAUNCHER_TRANSIENT_TASKBAR_SHOW);
        }
    }

    private void notifyStashChange(boolean visible, boolean stashed) {
        mSystemUiProxy.notifyTaskbarStatus(visible, stashed);
        setUpTaskbarSystemAction(visible);
        // If stashing taskbar is caused by IME visibility, we could just skip updating rounded
        // corner insets since the rounded corners will be covered by IME during IME is showing and
        // taskbar will be restored back to unstashed when IME is hidden.
        mControllers.taskbarActivityContext.updateInsetRoundedCornerFrame(
                    visible && !isStashedInAppIgnoringIme());
        mControllers.rotationButtonController.onTaskbarStateChange(visible, stashed);
    }

    /**
     * Setup system action for showing Taskbar depending on its visibility.
     */
    public void setUpTaskbarSystemAction(boolean visible) {
        UI_HELPER_EXECUTOR.execute(() -> {
            if (!visible || !DisplayController.isTransientTaskbar(mActivity)) {
                mAccessibilityManager.unregisterSystemAction(SYSTEM_ACTION_ID_TASKBAR);
                mIsTaskbarSystemActionRegistered = false;
                return;
            }

            if (!mIsTaskbarSystemActionRegistered) {
                RemoteAction taskbarRemoteAction = new RemoteAction(
                        Icon.createWithResource(mActivity, R.drawable.ic_info_no_shadow),
                        mActivity.getString(R.string.taskbar_a11y_title),
                        mActivity.getString(R.string.taskbar_a11y_title),
                        mTaskbarSharedState.taskbarSystemActionPendingIntent);

                mAccessibilityManager.registerSystemAction(taskbarRemoteAction,
                        SYSTEM_ACTION_ID_TASKBAR);
                mIsTaskbarSystemActionRegistered = true;
            }
        });
    }

    /**
     * Clean up on destroy from TaskbarControllers
     */
    public void onDestroy() {
        UI_HELPER_EXECUTOR.execute(
                () -> mAccessibilityManager.unregisterSystemAction(SYSTEM_ACTION_ID_TASKBAR));
    }

    /**
     * Cancels a timeout if any exists.
     */
    public void cancelTimeoutIfExists() {
        if (mTimeoutAlarm.alarmPending()) {
            mTimeoutAlarm.cancelAlarm();
        }
    }

    /**
     * Updates the status of the taskbar timeout.
     * @param isAutohideSuspended If true, cancels any existing timeout
     *                            If false, attempts to re/start the timeout
     */
    public void updateTaskbarTimeout(boolean isAutohideSuspended) {
        if (!DisplayController.isTransientTaskbar(mActivity)) {
            return;
        }
        if (isAutohideSuspended) {
            cancelTimeoutIfExists();
        } else {
            tryStartTaskbarTimeout();
        }
    }

    /**
     * Attempts to start timer to auto hide the taskbar based on time.
     */
    public void tryStartTaskbarTimeout() {
        if (!DisplayController.isTransientTaskbar(mActivity)
                || mIsStashed
                || mEnableBlockingTimeoutDuringTests) {
            return;
        }

        cancelTimeoutIfExists();

        mTimeoutAlarm.setOnAlarmListener(this::onTaskbarTimeout);
        mTimeoutAlarm.setAlarm(getTaskbarAutoHideTimeout());
    }

    /**
     * returns appropriate timeout for taskbar to stash depending on accessibility being on/off.
     */
    private long getTaskbarAutoHideTimeout() {
        return mAccessibilityManager.getRecommendedTimeoutMillis((int) NO_TOUCH_TIMEOUT_TO_STASH_MS,
                FLAG_CONTENT_CONTROLS);
    }

    private void onTaskbarTimeout(Alarm alarm) {
        if (mControllers.taskbarAutohideSuspendController.isSuspended()) {
            return;
        }
        updateAndAnimateTransientTaskbar(true);
    }

    @Override
    public void dumpLogs(String prefix, PrintWriter pw) {
        pw.println(prefix + "TaskbarStashController:");

        pw.println(prefix + "\tmStashedHeight=" + mStashedHeight);
        pw.println(prefix + "\tmUnstashedHeight=" + mUnstashedHeight);
        pw.println(prefix + "\tmIsStashed=" + mIsStashed);
        pw.println(prefix + "\tappliedState=" + getStateString(mStatePropertyHolder.mPrevFlags));
        pw.println(prefix + "\tmState=" + getStateString(mState));
        pw.println(prefix + "\tmIsSystemGestureInProgress=" + mIsSystemGestureInProgress);
        pw.println(prefix + "\tmIsImeShowing=" + mIsImeShowing);
        pw.println(prefix + "\tmIsImeSwitcherShowing=" + mIsImeSwitcherShowing);
    }

    private static String getStateString(int flags) {
        StringJoiner sj = new StringJoiner("|");
        appendFlag(sj, flags, FLAGS_IN_APP, "FLAG_IN_APP");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_MANUAL, "FLAG_STASHED_IN_APP_MANUAL");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_SYSUI, "FLAG_STASHED_IN_APP_SYSUI");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_SETUP, "FLAG_STASHED_IN_APP_SETUP");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_IME, "FLAG_STASHED_IN_APP_IME");
        appendFlag(sj, flags, FLAG_IN_STASHED_LAUNCHER_STATE, "FLAG_IN_STASHED_LAUNCHER_STATE");
        appendFlag(sj, flags, FLAG_STASHED_IN_TASKBAR_ALL_APPS, "FLAG_STASHED_IN_TASKBAR_ALL_APPS");
        appendFlag(sj, flags, FLAG_IN_SETUP, "FLAG_IN_SETUP");
        appendFlag(sj, flags, FLAG_STASHED_IN_APP_AUTO, "FLAG_STASHED_IN_APP_AUTO");
        appendFlag(sj, flags, FLAG_STASHED_SYSUI, "FLAG_STASHED_SYSUI");
        appendFlag(sj, flags, FLAG_STASHED_DEVICE_LOCKED, "FLAG_STASHED_DEVICE_LOCKED");
        return sj.toString();
    }

    private class StatePropertyHolder {
        private final IntPredicate mStashCondition;

        private boolean mIsStashed;
        private @StashAnimation int mLastStartedTransitionType = TRANSITION_DEFAULT;
        private int mPrevFlags;

        private long mLastUnlockTransitionTimeout = 0;

        StatePropertyHolder(IntPredicate stashCondition) {
            mStashCondition = stashCondition;
        }

        /**
         * Creates an animator (stored in mAnimator) which applies the latest state, potentially
         * creating a new animation (stored in mAnimator).
         * @param flags The latest flags to apply (see the top of this file).
         * @param duration The length of the animation.
         * @return mAnimator if mIsStashed changed, or {@code null} otherwise.
         */
        @Nullable
        public Animator createSetStateAnimator(int flags, long duration) {
            boolean isStashed = mStashCondition.test(flags);

            if (DEBUG) {
                String stateString = formatFlagChange(flags, mPrevFlags,
                        TaskbarStashController::getStateString);
                Log.d(TAG, "createSetStateAnimator: flags: " + stateString
                        + ", duration: " + duration
                        + ", isStashed: " + isStashed
                        + ", mIsStashed: " + mIsStashed);
            }

            int changedFlags = mPrevFlags ^ flags;
            if (mPrevFlags != flags) {
                onStateChangeApplied(changedFlags);
                mPrevFlags = flags;
            }

            boolean isUnlockTransition = hasAnyFlag(changedFlags, FLAG_STASHED_DEVICE_LOCKED)
                    && !hasAnyFlag(FLAG_STASHED_DEVICE_LOCKED);
            if (isUnlockTransition) {
                // the launcher might not be resumed at the time the device is considered
                // unlocked (when the keyguard goes away), but possibly shortly afterwards.
                // To play the unlock transition at the time the unstash animation actually happens,
                // this memoizes the state transition for UNLOCK_TRANSITION_MEMOIZATION_MS.
                mLastUnlockTransitionTimeout =
                        SystemClock.elapsedRealtime() + UNLOCK_TRANSITION_MEMOIZATION_MS;
            }

            @StashAnimation int animationType = computeTransitionType(changedFlags);

            // Allow re-starting animation if upgrading from default animation type, otherwise
            // stick with the already started transition.
            boolean transitionTypeChanged = mAnimator != null && mAnimator.isStarted()
                    && mLastStartedTransitionType == TRANSITION_DEFAULT
                    && animationType != TRANSITION_DEFAULT;

            if (mIsStashed != isStashed || transitionTypeChanged) {
                if (TestProtocol.sDebugTracing) {
                    Log.d(TestProtocol.TASKBAR_IN_APP_STATE, String.format(
                            "setState: mIsStashed=%b, isStashed=%b, "
                                    + "mAnimationType=%d, animationType=%d, duration=%d",
                            mIsStashed,
                            isStashed,
                            mLastStartedTransitionType,
                            animationType,
                            duration));
                }
                mIsStashed = isStashed;
                mLastStartedTransitionType = animationType;

                // This sets mAnimator.
                createAnimToIsStashed(mIsStashed, duration, animationType);
                return mAnimator;
            }
            return null;
        }

        private @StashAnimation int computeTransitionType(int changedFlags) {

            boolean hotseatHiddenDuringAppLaunch =
                    !mControllers.uiController.isHotseatIconOnTopWhenAligned()
                            && hasAnyFlag(changedFlags, FLAG_IN_APP);
            if (hotseatHiddenDuringAppLaunch) {
                // When launching an app from the all-apps drawer, the hotseat is hidden behind the
                // drawer. In this case, the navbar must just fade in, without a stash transition,
                // as the taskbar stash animation would otherwise be visible above the all-apps
                // drawer once the hotseat is detached.
                return TRANSITION_HANDLE_FADE;
            }

            boolean isUnlockTransition =
                    SystemClock.elapsedRealtime() < mLastUnlockTransitionTimeout;
            if (isUnlockTransition) {
                // When transitioning to unlocked device, the  hotseat will already be visible on
                // the homescreen, thus do not play an un-stash animation.
                // Keep isUnlockTransition in sync with its counterpart in
                // TaskbarLauncherStateController#onStateChangeApplied.
                return TRANSITION_HANDLE_FADE;
            }

            boolean homeToApp = hasAnyFlag(changedFlags, FLAG_IN_APP) && hasAnyFlag(FLAG_IN_APP);
            if (homeToApp) {
                return TRANSITION_HOME_TO_APP;
            }

            return TRANSITION_DEFAULT;
        }
    }
}
/*
 * Copyright (C) 2014-2017 SlimRoms Project
 * Author: Lars Greiss - email: kufikugel@googlemail.com
 * Copyright (C) 2017 ABC rom
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.systemui.slimrecent;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityManager.MemoryInfo;
import android.app.ActivityOptions;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
//import android.net.Uri;
import android.os.Handler;
//import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.text.TextUtils;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.android.internal.statusbar.IStatusBarService;

import com.android.systemui.R;
import com.android.systemui.recents.RecentsImplementation;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.ActivityOptionsCompat;
import com.android.systemui.slimrecent.icons.IconsHandler;
import com.android.systemui.statusbar.phone.StatusBar;

import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_RECENT_APPS;

import com.aicp.gear.util.ImageHelper;

/**
 * Our main recents controller.
 * Takes care of the toggle, preload, cancelpreload and close requests
 * and passes the requested actions trough to our views and the panel
 * view controller #link:RecentPanelView.
 *
 * As well the in out animation, constructing the main window container
 * and the remove all tasks animation/detection (#link:RecentListOnScaleGestureListener)
 * are handled here.
 */
public class RecentController implements RecentPanelView.OnExitListener,
        RecentPanelView.OnTasksLoadedListener, RecentsImplementation {

    private static final String TAG = "SlimRecentsController";

    // Animation control values.
    private static final int ANIMATION_STATE_NONE = 0;
    private static final int ANIMATION_STATE_OUT  = 1;

    // Animation state.
    private int mAnimationState = ANIMATION_STATE_NONE;

    private RecyclerView mCardRecyclerView;
    private Configuration mConfiguration;
    private Context mContext;
    private ActivityManager mAm;
    private IActivityManager mIam;
    private WindowManager mWindowManager;
    private IWindowManager mWindowManagerService;
    private IStatusBarService mStatusBarService;

    private CacheMoreCardsLayoutManager mLayoutManager;

    private boolean mIsShowing;
    private boolean mIsToggled;
    private boolean mIsPreloaded;

    private boolean mIsUserSetup;

    private boolean mExpandAnimation;

    // The different views we need.
    private ViewGroup mParentView;
    private ViewGroup mRecentContainer;
    private View mKeyguardView;
    private LinearLayout mRecentContent;
    private LinearLayout mRecentWarningContent;
    private ImageView mEmptyRecentView;
    private ImageView mKeyguardImage;
    private TextView mKeyguardText;

    private int mLayoutDirection = -1;
    private int mMainGravity;
    private int mUserGravity;
    private int mPanelColor;
    private int mVisibility;
    private int mEnterExitAnimation;

    TextView mMemText;
    ProgressBar mMemBar;
    boolean enableMemDisplay;
    private int mMembarcolor;
    private int mMemtextcolor;

    private boolean mMemBarLongClickToClear;

    private float mScaleFactor;

    private boolean mAicpEmptyView;

    // Main panel view.
    private RecentPanelView mRecentPanelView;

    // App Sidebar.
    private AppSidebar mAppSidebar;
    private boolean mAppSidebarEnabled;
    private boolean mAppSidebarAttached = false;
    private float mAppSidebarScaleFactor = AppSidebar.DEFAULT_SCALE_FACTOR;
    private boolean mAppSidebarOpenSimultaneously;

    private ArrayList<TaskDescription> mTasks = new ArrayList<>();
    private boolean mIsTopTaskInForeground;

    private Handler mHandler;

    private IconsHandler mIconsHandler;

    private boolean mWaitingClearAllConfirmation;
    private ObjectAnimator mClearAllAnimation;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            // Screen goes off or system dialogs should close.
            // Get rid of our recents screen
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                String reason = intent.getStringExtra("reason");
                if (reason != null &&
                        !reason.equals(SYSTEM_DIALOG_REASON_RECENT_APPS)) {
                    hideRecents(false);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)){
                hideRecents(true);
            }
        }
    };

    public RecentController() {
    }

    public void onStart(Context context) {
        mContext = context;
        mLayoutDirection = getLayoutDirection();
        mScaleFactor = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.RECENT_PANEL_SCALE_FACTOR, 115,
                UserHandle.USER_CURRENT) / 100.0f;

        mHandler = new Handler();
        mAm = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        mIam = ActivityManagerNative.getDefault();
        mWindowManager = (WindowManager)
                mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();

        /**
         * Add intent actions to listen on it.
         * Screen off to get rid of recents,
         * same if close system dialogs is requested.
         */
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter);
        mConfiguration = new Configuration();
        mConfiguration.updateFrom(context.getResources().getConfiguration());

        mParentView = new RecentBaseFrameLayout(mContext, this);

        // Inflate our recents layout
        mRecentContainer =
                (RelativeLayout) View.inflate(context, R.layout.slim_recent, null);

        // Get contents for rebuilding and gesture detector.
        mRecentContent =
                (LinearLayout) mRecentContainer.findViewById(R.id.recent_content);

        mRecentWarningContent =
                (LinearLayout) mRecentContainer.findViewById(R.id.recent_warning_content);

        mCardRecyclerView =
                (RecyclerView) mRecentContainer.findViewById(R.id.recent_list);

        mAm = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mMemText = (TextView) mRecentContainer.findViewById(R.id.recents_memory_text);
        mMemBar = (ProgressBar) mRecentContainer.findViewById(R.id.recents_memory_bar);
        mRecentContainer.findViewById(R.id.recents_membar)
                .setOnLongClickListener(mMemBarLongClickListener);

        mCardRecyclerView.setHasFixedSize(true);

        mEmptyRecentView =
                (ImageView) mRecentContainer.findViewById(R.id.empty_recent);

        mKeyguardView = View.inflate(context, R.layout.slim_recent_keyguard, null);

        mKeyguardImage =
                (ImageView) mKeyguardView.findViewById(R.id.keyguard_recent_img);

        mKeyguardText = (TextView) mKeyguardView.findViewById(R.id.keyguard_recent_text);

        mClearAllAnimation = ObjectAnimator.ofPropertyValuesHolder(
                mCardRecyclerView,
                PropertyValuesHolder.ofFloat("alpha", 0.3f)/*,
                maybe add more anim props here like scaleX and Y*/);
        mClearAllAnimation.setDuration(850);
        mClearAllAnimation.setRepeatCount(ObjectAnimator.INFINITE);
        mClearAllAnimation.setRepeatMode(ObjectAnimator.REVERSE);


        // Prepare gesture detector.
        final ScaleGestureDetector recentListGestureDetector =
                new ScaleGestureDetector(mContext,
                        new RecentListOnScaleGestureListener(mRecentWarningContent));

        // Prepare recents panel view and set the listeners
        mCardRecyclerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mWaitingClearAllConfirmation) {
                    cancelClearAllWaiting();
                }
                recentListGestureDetector.onTouchEvent(event);
                return false;
            }
        });

        mRecentPanelView = new RecentPanelView(mContext, this, mCardRecyclerView, mEmptyRecentView);
        mRecentPanelView.setOnExitListener(this);
        mRecentPanelView.setOnTasksLoadedListener(this);

        // Add finally the views and listen for outside touches.
        mParentView.setFocusableInTouchMode(true);
        mParentView.addView(mRecentContainer);
        mParentView.addView(mKeyguardView);
        mParentView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    // Touch outside the recents window....hide recents window.
                    onExit();
                    return true;
                }
                return false;
            }
        });

        mIconsHandler = new IconsHandler(mContext, R.dimen.recent_app_icon_size, mScaleFactor);
        mRecentPanelView.setIconsHandler(mIconsHandler);

        // Settings observer
        new SettingsObserver(mHandler).observe();
        new KeepOpenSettingsObserver(mHandler).observe();

        mContext.registerComponentCallbacks(new ComponentCallback());
    }

    private IStatusBarService getStatusBarService() {
        if (mStatusBarService == null) {
            mStatusBarService = IStatusBarService.Stub.asInterface(
                    ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        }
        return mStatusBarService;
    }

    public void refreshCachedPackage(String packageName, boolean removedPackage) {
        CacheController.getInstance(mContext, null).refreshPackage(packageName, removedPackage);
        InfosCacheController.getInstance(mContext).refreshPackage(packageName);
    }

    public void evictAllCaches() {
        //ThumbnailsCacheController.getInstance(mContext).clearCache();
        CacheController.getInstance(mContext, null).clearCache();
        InfosCacheController.getInstance(mContext).clearCache();
    }

    public void trimCaches(boolean lowMem) {
        int maxMemory;
        if (lowMem) {
            /*maxMemory = ThumbnailsCacheController.getInstance(mContext).getMaxMemory();
            ThumbnailsCacheController.getInstance(mContext).trimToSize(maxMemory / 6);*/
            maxMemory = CacheController.getInstance(mContext, null).getMaxMemory();
            CacheController.getInstance(mContext, null).trimToSize(maxMemory / 8);
            InfosCacheController.getInstance(mContext).trimToSize(10);
        } else {
            maxMemory = CacheController.getInstance(mContext, null).getMaxMemory();
            CacheController.getInstance(mContext, null).trimToSize(maxMemory / 6);
            InfosCacheController.getInstance(mContext).trimToSize(20);
        }
    }

    /**
     * External call from theme engines to apply
     * new styles.
     */
    public void rebuildRecentsScreen() {
        // Set new layout parameters and backgrounds.
        if (mRecentContainer != null) {
            final ViewGroup.LayoutParams layoutParams = mRecentContainer.getLayoutParams();
            layoutParams.width = (int) (mContext.getResources()
                    .getDimensionPixelSize(R.dimen.recent_width) * mScaleFactor);
            mRecentContainer.setLayoutParams(layoutParams);

            setGravityAndImageResources();
        }
        // Rebuild complete adapter and lists to force style updates.
        if (mRecentPanelView != null) {
            mRecentPanelView.buildCardListAndAdapter();
        }
    }

    /**
     * Calculate main gravity based on layout direction and user gravity value.
     * Set and update all resources and notify the different layouts about the change.
     */
    private void setGravityAndImageResources() {
        // Calculate and set gravitiy.
        if (mLayoutDirection == View.LAYOUT_DIRECTION_RTL) {
            mMainGravity = reverseGravity(mUserGravity);
        } else {
            mMainGravity = mUserGravity;
        }

        // Set layout direction.
        mRecentContainer.setLayoutDirection(mLayoutDirection);

        // Reset all backgrounds.
        mRecentContent.setBackgroundResource(0);
        mRecentWarningContent.setBackgroundResource(0);
        mEmptyRecentView.setImageResource(0);

        // Set correct backgrounds based on calculated main gravity.
        mRecentWarningContent.setBackgroundColor(Color.RED);

        int tintColor = getEmptyRecentColor();
        int backgroundColor = mPanelColor;
        if (backgroundColor == 0x00ffffff) {
            backgroundColor = mContext.getResources().getColor(R.color.recent_background);
        }

        if (mAicpEmptyView) {
            // AICP empty recents drawable
            AnimatedVectorDrawable vd = (AnimatedVectorDrawable)
                    mContext.getResources().getDrawable(R.drawable.aicp_no_recents_slim, null);
            vd.setTint(tintColor);
            mEmptyRecentView.setImageDrawable(vd);
        } else {
            // Default empty recents drawable
            VectorDrawable vd = (VectorDrawable)
                    mContext.getResources().getDrawable(R.drawable.ic_empty_recent);
            vd.setTint(tintColor);
            mEmptyRecentView.setImageDrawable(vd);
        }

        VectorDrawable vd = (VectorDrawable)
                mContext.getResources().getDrawable(R.drawable.ic_recent_keyguard);
        vd.setTint(tintColor);
        mKeyguardImage.setImageDrawable(vd);
        mKeyguardText.setTextColor(tintColor);

        int padding = mContext.getResources().getDimensionPixelSize(R.dimen.slim_recents_elevation);
        if (mMainGravity == Gravity.START) {
            mRecentContainer.setPadding(0, 0, padding, 0);
            mEmptyRecentView.setRotation(mAicpEmptyView ? 0 : 180);
        } else {
            mRecentContainer.setPadding(padding, 0, 0, 0);
            mEmptyRecentView.setRotation(0);
        }


        // Set custom background color (or reset to default, as the case may be
        if (mRecentContent != null) {
            mRecentContent.setElevation(50);
            mRecentContent.setBackgroundColor(backgroundColor);
        }

        if (mKeyguardView != null) {
            mKeyguardView.setBackgroundColor(backgroundColor);
        }
    }

    private int getEmptyRecentColor() {
        int color = mPanelColor == 0x00ffffff ?
                mContext.getResources().getColor(R.color.recent_background) : mPanelColor;
        if (Utilities.computeContrastBetweenColors(color,
                Color.WHITE) < 3f) {
            return mContext.getResources().getColor(
                    R.color.recents_empty_dark_color);
        } else {
            return mContext.getResources().getColor(
                    R.color.recents_empty_light_color);
        }
    }

    private int getLayoutDirection() {
        final Configuration currentConfig = mContext.getResources()
                .getConfiguration();
        Locale locale = currentConfig.locale;
        return TextUtils.getLayoutDirectionFromLocale(locale);
    }

    @Override
    public void toggleRecentApps() {
        if (!mIsUserSetup) {
            return;
        }
        toggle();
    }

    private void toggle() {
        int ld = getLayoutDirection();
        if (mLayoutDirection != ld) {
            mLayoutDirection = ld;
            setGravityAndImageResources();
        }

        if (mAnimationState == ANIMATION_STATE_NONE) {
            if (!isShowing()) {
                mIsToggled = true;
                if (!mIsPreloaded) {
                    // This should never happen due that preload should
                    // always be done if someone calls recents. Well a lot
                    // 3rd party apps forget the preload step. So we do it now.
                    // Due that mIsToggled is true preloader will open the recent
                    // screen as soon the preload is finished and the listener
                    // notifies us that we are ready.
                    preloadRecentApps();
                } else if (mRecentPanelView.atLeastOneTaskAvailable()) {
                    showRecents();
                }
            } else {
                openLastAppPanelToggle();
                hideRecents(false);
            }
        }
    }

    @Override
    public boolean splitPrimaryTask(int stackCreateMode, Rect initialBounds,
                                    int metricsDockAction) {
        /* TODO
        SystemServicesProxy ssp = SystemServicesProxy.getInstance(mContext);
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        if (runningTask == null) {
            return false;
        }
        final int activityType = runningTask.configuration.windowConfiguration.getActivityType();
        boolean screenPinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        boolean isRunningTaskInHomeOrRecentsStack =
                activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS;
        if (isRunningTaskInHomeOrRecentsStack || screenPinningActive) {
            return false;
        }
        int createMode = ActivityManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
        Point realSize = new Point();
        mContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                .getRealSize(realSize);
        boolean isLandscape = mContext.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        // dock the stack to half the screen
        Rect initialBounds = new Rect(0, 0, isLandscape ? (int)(realSize.x/2) : realSize.x,
                isLandscape ? realSize.y : (int)(realSize.y/2));
        if (ssp.setTaskWindowingModeSplitScreenPrimary(runningTask.id, createMode, initialBounds)) {
            if (!isShowing()) {
                showRecents();
            }
            return true;
        }
        */
        return false;
    }

    protected void startTaskinMultiWindow(int id) {
        final ActivityOptions options =
                ActivityOptionsCompat.makeSplitScreenOptions(true/*dockTopLeft*/);
        if (ActivityManagerWrapper.getInstance().startActivityFromRecents(id, options)) {
            openLastApptoBottom();
        }
   }

    private void openLastApptoBottom() {

        int taskid = 0;
        boolean doWeHaveAtask = true;

        ActivityManager.RunningTaskInfo lastTask = getLastTask(mAm);
        if (lastTask != null) {
            // available task in this stack, we can dock it to the other side
            taskid = lastTask.id;
        } else {
            // let's search in recent apps list
            List<ActivityManager.RecentTaskInfo> recentTasks = getRecentTasks();
            if (recentTasks != null && recentTasks.size() > 1) {
                ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(1);
                taskid = recentInfo.persistentId;
            } else  {
                // user cleared all apps, we don't have any taskid to choose
                doWeHaveAtask = false;
            }
        }
        if (doWeHaveAtask) {
            try {
                mIam.startActivityFromRecents(taskid, getAnimation(mContext).toBundle());
            } catch (RemoteException e) {}
        }
    }

    private ActivityManager.RunningTaskInfo getLastTask(final ActivityManager am) {
        final String defaultHomePackage = resolveCurrentLauncherPackage();
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(5);
        for (int i = 1; i < tasks.size(); i++) {
            String packageName = tasks.get(i).topActivity.getPackageName();
            if (!packageName.equals(defaultHomePackage)
                    && !packageName.equals(mContext.getPackageName())
                    && !packageName.equals("com.android.systemui")) {
                return tasks.get(i);
            }
        }
        return null;
    }

    private List<ActivityManager.RecentTaskInfo> getRecentTasks() {
        return mAm.getRecentTasks(ActivityManager.getMaxRecentTasksStatic(),
                ActivityManager.RECENT_IGNORE_UNAVAILABLE);
    }

    protected void addTasks(TaskDescription task) {
        mTasks.add(task);
    }

    protected void resetTasks() {
        mTasks.clear();
    }

    protected void isTopTaskInForeground(boolean toptask) {
        mIsTopTaskInForeground = toptask;
    }

    private void openLastAppPanelToggle() {
        if (mTasks != null && !mTasks.isEmpty()) {
            if (!mIsTopTaskInForeground) {
                startApplication(mTasks.get(0));
            } else if (mTasks.size() > 1) {
                startApplication(mTasks.get(1));
            }
        }
    }

    protected void startApplication(TaskDescription td) {
        // Starting app is requested by the user.
        // Move it to foreground or start it with custom animation.
        if (td.taskId >= 0) {
            // This is an active task; it should just go to the foreground.
            mAm.moveTaskToFront(td.taskId, ActivityManager.MOVE_TASK_WITH_HOME,
                    getAnimation(mContext).toBundle());
        } else {
            //startContainerActivity(mContext); // see notes
            final Intent intent = td.intent;
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
                    | Intent.FLAG_ACTIVITY_TASK_ON_HOME
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mContext.startActivityAsUser(intent, getAnimation(mContext).toBundle(),
                        new UserHandle(UserHandle.USER_CURRENT));
            } catch (SecurityException e) {
                Log.e(TAG, "Recents does not have the permission to launch " + intent, e);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Error launching activity " + intent, e);
            }
        }
    }

    /**
     * Get custom animation for app starting.
     * @return Bundle
     */
    protected static ActivityOptions getAnimation(Context context) {
        return ActivityOptions.makeCustomAnimation(context,
                R.anim.recent_enter,
                R.anim.recent_screen_fade_out);
    }

    private String resolveCurrentLauncherPackage() {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = mContext.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivity(launcherIntent, 0);
        return launcherInfo.activityInfo.packageName;
    }

    /**
     * External call. Preload recent tasks.
     */
    @Override
    public void preloadRecentApps() {
        if (!mIsUserSetup) {
            return;
        }
        // Post this to ensure that we don't block the touch feedback
        // on the nav bar button which triggers this.
        mHandler.post(() -> {
           if (mRecentPanelView != null) {
                mIsPreloaded = true;
                setSystemUiVisibilityFlags();
                mRecentPanelView.setCancelledByUser(false);
                mRecentPanelView.loadTasks();
            }
        });
    }

    /**
     * External call. Cancel preload recent tasks.
     */
    @Override
    public void cancelPreloadRecentApps() {
        if (!mIsUserSetup) {
            return;
        }
        if (mIsToggled) {
            Log.w(TAG, "cancelPreloadRecentApps() called after toggle, discarding");
            return;
        }
        if (mRecentPanelView != null && !isShowing()) {
            mIsPreloaded = false;
            mRecentPanelView.setCancelledByUser(true);
        }
    }

    protected void closeRecents() {
        hideRecents(false);
    }

    /**
     * Get LayoutParams we need for the recents panel.
     *
     * @return LayoutParams
     */
    private WindowManager.LayoutParams generateLayoutParameter(){
        return generateLayoutParameter(false);
    }

    /**
     * Get LayoutParams we need for the recents panel or the recents app sidebar.
     *
     * @return LayoutParams
     */
    private WindowManager.LayoutParams generateLayoutParameter(boolean forAppSidebar) {
        int width;
        if (forAppSidebar) {
            int appSidebarPadding = mContext.getResources()
                    .getDimensionPixelSize(R.dimen.recent_app_sidebar_item_padding);
            width = (int) (mContext.getResources()
                    .getDimensionPixelSize(R.dimen.recent_app_sidebar_item_size)
                    * mAppSidebarScaleFactor + appSidebarPadding * 2f);
        } else {
            width = (int) (mContext.getResources().getDimensionPixelSize(R.dimen.recent_width)
                    * mScaleFactor);
        }
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        // Turn on hardware acceleration for high end gfx devices.
        if (ActivityManager.isHighEndGfx()) {
            params.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            params.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
        }

        // Set gravitiy.
        if (forAppSidebar) {
            params.gravity = reverseGravity(mMainGravity);
        } else {
            params.gravity = mMainGravity;
        }
        params.gravity |= Gravity.CENTER_VERTICAL;

        // Set animation for our recent window.
        switch (mEnterExitAnimation) {
            case 1:
                params.windowAnimations = R.style.Animation_SlimRecentScreen;
                break;
            case 0:
            default:
                if ((mMainGravity == Gravity.START) != forAppSidebar) {
                    params.windowAnimations = R.style.Animation_SlimRecentScreenOrig_Left;
                } else {
                    params.windowAnimations = R.style.Animation_SlimRecentScreenOrig_Right;
                }
                break;
        }

        // This title is for debugging only. See: dumpsys window
        params.setTitle(forAppSidebar ? "RecentAppSidebar" : "RecentControlPanel");
        return params;
    }

    /**
     * For smooth user experience we attach the same systemui visbility
     * flags the current app, where the user is on, has set.
     */
    private void setSystemUiVisibilityFlags() {
        int vis = 0;
        boolean layoutBehindNavigation = true;
        int newVis = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if ((vis & View.STATUS_BAR_TRANSLUCENT) != 0) {
            newVis |= View.STATUS_BAR_TRANSLUCENT
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        if ((vis & View.NAVIGATION_BAR_TRANSLUCENT) != 0) {
            newVis |= View.NAVIGATION_BAR_TRANSLUCENT;
        }
        if ((vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0) {
            newVis |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            layoutBehindNavigation = false;
        }
        if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) {
            newVis |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        if ((vis & View.SYSTEM_UI_FLAG_IMMERSIVE) != 0) {
            newVis |= View.SYSTEM_UI_FLAG_IMMERSIVE;
            layoutBehindNavigation = false;
        }
        if ((vis & View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0) {
            newVis |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            layoutBehindNavigation = false;
        }
        if (layoutBehindNavigation) {
            newVis |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        mParentView.setSystemUiVisibility(newVis);
        mVisibility = newVis;
        if (mAppSidebar != null){
            mAppSidebar.setSystemUiVisibility(newVis);
        }
    }

    // Returns if panel is currently showing.
    public boolean isShowing() {
        return mIsShowing;
    }

    public void scrollPanel(boolean down) {
        if (mWaitingClearAllConfirmation) {
            cancelClearAllWaiting();
        }
        mRecentPanelView.scrollPanel(down);
    }

    public void clearAllAppsFromSwipe() {
        if (mTasks == null || mTasks.isEmpty()) {
            return;
        }
        if (!mWaitingClearAllConfirmation) {
            mClearAllAnimation.start();
            mWaitingClearAllConfirmation = true;
        } else {
            cancelClearAllWaiting();
            if (mRecentPanelView.removeAllApplications()) {
                hideRecents(false);
            }
        }
    }

    protected void cancelClearAllWaiting() {
        mClearAllAnimation.cancel();
        mCardRecyclerView.setAlpha(1.0f);
        mWaitingClearAllConfirmation = false;
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        hideRecents(triggeredFromHomeKey);
    }

    // Hide the recent window.
    public boolean hideRecents(boolean forceHide) {
        if (!mIsUserSetup) {
            return false;
        }
        if (isShowing()) {
            mIsPreloaded = false;
            mIsToggled = false;
            mIsShowing = false;
            // stop async task if still loading
            mRecentPanelView.setCancelledByUser(true);
            if (forceHide) {
                mAnimationState = ANIMATION_STATE_NONE;
                mHandler.removeCallbacks(mRecentRunnable);
                mWindowManager.removeViewImmediate(mParentView);
                removeSidebarViewImmediate();
                return true;
            } else if (mAnimationState != ANIMATION_STATE_OUT) {
                mAnimationState = ANIMATION_STATE_OUT;
                mHandler.removeCallbacks(mRecentRunnable);
                mHandler.postDelayed(mRecentRunnable, mContext.getResources().getInteger(
                        R.integer.config_recentDefaultDur));
                mWindowManager.removeView(mParentView);
                removeSidebarView();
                return true;
            }
        }
        return false;
    }

    // Show the recent window.
    private void showRecents() {
        mIsShowing = true;
        cancelClearAllWaiting();
        sendCloseSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);
        mAnimationState = ANIMATION_STATE_NONE;
        mHandler.removeCallbacks(mRecentRunnable);
        mWindowManager.addView(mParentView, generateLayoutParameter());
        mRecentPanelView.scrollToFirst();

        KeyguardManager km =
                (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        String restrictionMsg = null;
        if (km.inKeyguardRestrictedInputMode()) {
            restrictionMsg = mContext.getString(R.string.slim_recent_keyguard);
        }
        try {
            if (mIam.isInLockTaskMode()) {
                restrictionMsg = mContext.getString(R.string.slim_recent_pinned);
            }
        } catch (RemoteException e) {}
        if (restrictionMsg == null) {
            mRecentContainer.setVisibility(View.VISIBLE);
            mKeyguardView.setVisibility(View.GONE);
            addSidebarView();
        } else {
            mKeyguardText.setText(restrictionMsg);
            mRecentContainer.setVisibility(View.GONE);
            mKeyguardView.setVisibility(View.VISIBLE);
        }
    }

    protected static void sendCloseSystemWindows(String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {}
        }
    }

    // Listener callback.
    @Override
    public void onExit() {
        hideRecents(false);
    }

    // Listener callback.
    @Override
    public void onTasksLoaded() {
        if (mIsToggled && !isShowing()) {
            showRecents();
        }
    }

    /**
     * Runnable if recent panel closed to notify the cache controller about the state.
     */
    private final Runnable mRecentRunnable = new Runnable() {
        @Override
        public void run() {
            mAnimationState = ANIMATION_STATE_NONE;
        }
    };

    /**
     * Settingsobserver to take care of the user settings.
     * Either gravity or scale factor of our recent panel can change.
     */
    private class SettingsObserver extends UserContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_PANEL_GRAVITY),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_PANEL_SCALE_FACTOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_PANEL_EXPANDED_MODE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_PANEL_BG_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_RECENT_AICP_EMPTY_DRAWABLE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.USE_RECENT_APP_SIDEBAR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_APP_SIDEBAR_CONTENT),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_APP_SIDEBAR_SCALE_FACTOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_APP_SIDEBAR_OPEN_SIMULTANEOUSLY),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_RECENTS_MEM_DISPLAY),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_RECENTS_MEM_DISPLAY_LONG_CLICK_CLEAR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_CARD_BG_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_RECENTS_ICON_PACK),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.LOCK_TO_APP_ENABLED),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENTS_MAX_APPS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_MEM_BAR_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_MEM_TEXT_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_RECENTS_CORNER_RADIUS),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.DEVICE_PROVISIONED),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.USER_SETUP_COMPLETE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_RECENTS_BLACKLIST_VALUES),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SLIM_RECENT_ENTER_EXIT_ANIMATION),
                    false, this, UserHandle.USER_ALL);
            update(true);
        }

        @Override
        protected void update(boolean firstBoot) {
            // Close recent panel if it is opened
            hideRecents(false);

            ContentResolver resolver = mContext.getContentResolver();

            int expandMode = Settings.System.getIntForUser(
                        resolver, Settings.System.RECENT_PANEL_EXPANDED_MODE,
                        RecentPanelView.EXPANDED_MODE_AUTO,
                        UserHandle.USER_CURRENT);
            mLayoutManager =
                    new CacheMoreCardsLayoutManager(mContext, mWindowManager, expandMode);
            mLayoutManager.setReverseLayout(true);
            mCardRecyclerView.setLayoutManager(mLayoutManager);
            mCardRecyclerView.setItemAnimator(mItemAnimator);

            // Get user gravity.
            mUserGravity = Settings.System.getIntForUser(
                    resolver, Settings.System.RECENT_PANEL_GRAVITY, Gravity.END,
                    UserHandle.USER_CURRENT);

            mEnterExitAnimation = Settings.System.getIntForUser(
                    resolver, Settings.System.SLIM_RECENT_ENTER_EXIT_ANIMATION, 0,
                    UserHandle.USER_CURRENT);

            mAicpEmptyView = Settings.System.getIntForUser(resolver,
                    Settings.System.SLIM_RECENT_AICP_EMPTY_DRAWABLE, 1,
                    UserHandle.USER_CURRENT) == 1;

            // Update colors in RecentPanelView
            mPanelColor = Settings.System.getIntForUser(resolver,
                    Settings.System.RECENT_PANEL_BG_COLOR, 0x00ffffff, UserHandle.USER_CURRENT);

            // Set main gravity and background images.
            setGravityAndImageResources();

            // Get user scale factor.
            float scaleFactor = Settings.System.getIntForUser(
                    resolver, Settings.System.RECENT_PANEL_SCALE_FACTOR, 100,
                    UserHandle.USER_CURRENT) / 100.0f;

            // If changed set new scalefactor, rebuild the recent panel
            // and notify RecentPanelView about new value.
            if (scaleFactor != mScaleFactor) {
                mScaleFactor = scaleFactor;
                rebuildRecentsScreen();
                CacheController.getInstance(mContext, null).clearCache();
                mIconsHandler.refresh();
                mIconsHandler.setScaleFactor(scaleFactor);
                //ThumbnailsCacheController.getInstance(mContext).clearCache();
            }

            if (mRecentPanelView != null) {
                mRecentPanelView.setScaleFactor(mScaleFactor);
                mRecentPanelView.setExpandedMode(expandMode);
                mRecentPanelView.setCardColor(Settings.System.getIntForUser(
                    resolver, Settings.System.RECENT_CARD_BG_COLOR, 0x00ffffff,
                    UserHandle.USER_CURRENT));
                mRecentPanelView.isScreenPinningEnabled(Settings.System.getIntForUser(
                        resolver, Settings.System.LOCK_TO_APP_ENABLED, 0,
                        UserHandle.USER_CURRENT) == 1);
                mRecentPanelView.setMaxAppsToLoad(Settings.System.getIntForUser(
                        resolver, Settings.System.RECENTS_MAX_APPS, 15,
                        UserHandle.USER_CURRENT));
                mRecentPanelView.setCornerRadius(Converter.floatDpToPx(mContext,
                        Settings.System.getIntForUser(resolver,
                                Settings.System.SLIM_RECENTS_CORNER_RADIUS, 5,
                                UserHandle.USER_CURRENT)));
                mRecentPanelView.setBlackList(Settings.System.getStringForUser(
                        resolver, Settings.System.SLIM_RECENTS_BLACKLIST_VALUES,
                        UserHandle.USER_CURRENT));
            }

            mRecentContent.setElevation(50);

            int backgroundColor = mPanelColor;
            if (backgroundColor == 0x00ffffff) {
                backgroundColor = mContext.getResources().getColor(R.color.recent_background);
            }
            mRecentContent.setBackgroundColor(backgroundColor);
            mKeyguardView.setBackgroundColor(backgroundColor);

            // App sidebar settings
            if (Settings.System.getIntForUser(resolver, Settings.System.USE_RECENT_APP_SIDEBAR, 1,
                    UserHandle.USER_CURRENT) == 1) {
                String appSidebarContent = Settings.System.getStringForUser(resolver,
                        Settings.System.RECENT_APP_SIDEBAR_CONTENT, UserHandle.USER_CURRENT);
                mAppSidebarEnabled = appSidebarContent != null && !appSidebarContent.equals("");
            } else {
                mAppSidebarEnabled = false;
            }
            mAppSidebarScaleFactor = Settings.System.getIntForUser(
                    resolver, Settings.System.RECENT_APP_SIDEBAR_SCALE_FACTOR, 100,
                    UserHandle.USER_CURRENT) / 100.0f;
            mAppSidebarOpenSimultaneously = Settings.System.getIntForUser(resolver,
                    Settings.System.RECENT_APP_SIDEBAR_OPEN_SIMULTANEOUSLY, 1,
                    UserHandle.USER_CURRENT) == 1;

            enableMemDisplay = Settings.System.getInt(resolver,
                    Settings.System.SLIM_RECENTS_MEM_DISPLAY, 0) == 1;
            showMemDisplay();

            mMemBarLongClickToClear = Settings.System.getInt(resolver,
                    Settings.System.SLIM_RECENTS_MEM_DISPLAY_LONG_CLICK_CLEAR, 0) == 1;
            mMembarcolor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SLIM_MEM_BAR_COLOR, 0x00ffffff);
            mMemtextcolor = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SLIM_MEM_TEXT_COLOR, 0x00ffffff);

            String currentIconPack = Settings.System.getString(resolver,
                Settings.System.SLIM_RECENTS_ICON_PACK);
            CacheController.getInstance(mContext, null).clearCache();
            mIconsHandler.updatePrefs(currentIconPack);

            mIsUserSetup = Settings.Global.getInt(resolver,
                    Settings.Global.DEVICE_PROVISIONED, 0) != 0
                    && Settings.Secure.getInt(resolver,
                    Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;

            // force a new preloading on next Recents call after boot or a settings change
            // to refresh the panel before the user shows it again.
            mIsPreloaded = false;
        }
    }

    /**
     * Settingsobserver to take care of the user settings that don't require closing the panel.
     */
    private class KeepOpenSettingsObserver extends UserContentObserver {
        KeepOpenSettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        protected void observe() {
            super.observe();
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.RECENT_PANEL_FAVORITES),
                    false, this, UserHandle.USER_ALL);
            update(true);
        }

        @Override
        protected void update(boolean firstBoot) {
            ContentResolver resolver = mContext.getContentResolver();
            if (mRecentPanelView != null) {
                mRecentPanelView.setCurrentFavorites(Settings.System.getStringForUser(
                        resolver, Settings.System.RECENT_PANEL_FAVORITES,
                        UserHandle.USER_CURRENT));
            }
        }
    }

    public LinearLayoutManager getLayoutManager() {
        return (LinearLayoutManager) mLayoutManager;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mConfiguration.densityDpi != newConfig.densityDpi) {
            hideRecents(true);
            evictAllCaches();
            mIconsHandler.onDpiChanged(mContext);
            rebuildRecentsScreen();
            preloadRecentApps();
        }
        mConfiguration.updateFrom(newConfig);
    }

    /**
     * Extended SimpleOnScaleGestureListener to take
     * care of a pinch to zoom out gesture. This class
     * takes as well care on a bunch of animations which are needed
     * to control the final action.
     */
    private class RecentListOnScaleGestureListener extends SimpleOnScaleGestureListener {

        // Constants for scaling max/min values.
        private final static float MAX_SCALING_FACTOR       = 1.0f;
        private final static float MIN_SCALING_FACTOR       = 0.5f;
        private final static float MIN_ALPHA_SCALING_FACTOR = 0.55f;

        private final static int ANIMATION_FADE_IN_DURATION  = 400;
        private final static int ANIMATION_FADE_OUT_DURATION = 300;

        private float mScalingFactor = MAX_SCALING_FACTOR;
        private boolean mActionDetected;

        // Views we need and are passed trough the constructor.
        private LinearLayout mRecentWarningContent;

        RecentListOnScaleGestureListener(LinearLayout recentWarningContent) {
            mRecentWarningContent = recentWarningContent;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Get gesture scaling factor and calculate the values we need.
            mScalingFactor *= detector.getScaleFactor();
            mScalingFactor = Math.max(MIN_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));
            final float alphaValue = Math.max(MIN_ALPHA_SCALING_FACTOR,
                    Math.min(mScalingFactor, MAX_SCALING_FACTOR));

            // Reset detection value.
            mActionDetected = false;

            // Set alpha value for content.
            mRecentContent.setAlpha(alphaValue);

            // Check if we are under MIN_ALPHA_SCALING_FACTOR and show
            // warning view.
            if (mScalingFactor < MIN_ALPHA_SCALING_FACTOR) {
                mActionDetected = true;
                mRecentWarningContent.setVisibility(View.VISIBLE);
            } else {
                mRecentWarningContent.setVisibility(View.GONE);
            }
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return mRecentPanelView.hasClearableTasks();
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            super.onScaleEnd(detector);
            // Reset to default scaling factor to prepare for next gesture.
            mScalingFactor = MAX_SCALING_FACTOR;

            final float currentAlpha = mRecentContent.getAlpha();

            // Gesture was detected and activated. Prepare and play the animations.
            if (mActionDetected) {
                final boolean hasFavorite = mRecentPanelView.hasFavorite();

                // Setup animation for warning content - fade out.
                ValueAnimator animation1 = ValueAnimator.ofFloat(1.0f, 0.0f);
                animation1.setDuration(ANIMATION_FADE_OUT_DURATION);
                animation1.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentWarningContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for list view - fade out.
                ValueAnimator animation2 = ValueAnimator.ofFloat(1.0f, 0.0f);
                animation2.setDuration(ANIMATION_FADE_OUT_DURATION);
                animation2.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mCardRecyclerView.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for base content - fade in.
                ValueAnimator animation3 = ValueAnimator.ofFloat(currentAlpha, 1.0f);
                animation3.setDuration(ANIMATION_FADE_IN_DURATION);
                animation3.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Setup animation for empty recent image - fade in.
                if (!hasFavorite && !mAicpEmptyView) {
                    mEmptyRecentView.setAlpha(0.0f);
                    mEmptyRecentView.setVisibility(View.VISIBLE);
                }
                ValueAnimator animation4 = ValueAnimator.ofFloat(0.0f, 1.0f);
                animation4.setDuration(ANIMATION_FADE_IN_DURATION);
                animation4.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mEmptyRecentView.setAlpha((Float) animation.getAnimatedValue());
                    }
                });

                // Start all ValueAnimator animations
                // and listen onAnimationEnd to prepare the views for the next call.
                AnimatorSet animationSet = new AnimatorSet();
                if (hasFavorite || mAicpEmptyView) {
                    animationSet.playTogether(animation1, animation3);
                } else {
                    animationSet.playTogether(animation1, animation2, animation3, animation4);
                }
                animationSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Animation is finished. Prepare warning content for next call.
                        mRecentWarningContent.setVisibility(View.GONE);
                        mRecentWarningContent.setAlpha(1.0f);
                        // Remove all tasks now.
                        if (mRecentPanelView.removeAllApplications()) {
                            // Prepare listview for next recent call.
                            mCardRecyclerView.setVisibility(View.GONE);
                            mCardRecyclerView.setAlpha(1.0f);
                            // Finally hide our recents screen.
                            hideRecents(false);
                        }
                    }
                });
                animationSet.start();

            } else if (currentAlpha < 1.0f) {
                // No gesture action was detected. But we may have a lower alpha
                // value for the content. Animate back to full opacitiy.
                ValueAnimator animation = ValueAnimator.ofFloat(currentAlpha, 1.0f);
                animation.setDuration(100);
                animation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mRecentContent.setAlpha((Float) animation.getAnimatedValue());
                    }
                });
                animation.start();
            }
        }
    }

    private int reverseGravity(int gravity){
        return gravity == Gravity.START ? Gravity.END : Gravity.START;
    }

    // Methods for app sidebar:
    private void addSidebarView() {
        addSidebarHandler.removeCallbacks(addSidebarRunnable);
        if (mAppSidebarEnabled) {
            if (mAppSidebarOpenSimultaneously) {
                addSidebarRunnable.run();
            } else {
                addSidebarHandler.post(addSidebarRunnable);
            }
        }
    }

    private Handler addSidebarHandler = new Handler();

    private Runnable addSidebarRunnable =
            new Runnable() {
                @Override
                public void run() {
                    mAppSidebarAttached = true;
                    if (mAppSidebar == null) {
                        mAppSidebar = (AppSidebar) View.inflate(mContext,
                                R.layout.recent_app_sidebar, null);
                    }
                    mAppSidebar.setSlimRecent(RecentController.this);
                    mAppSidebar.setSystemUiVisibility(mVisibility);
                    mWindowManager.addView(mAppSidebar, generateLayoutParameter(true));
                }
            };

    private void removeSidebarView() {
        addSidebarHandler.removeCallbacks(addSidebarRunnable);
        if (mAppSidebarAttached) {
            mAppSidebar.launchPendingSwipeAction();
            mWindowManager.removeView(mAppSidebar);
            mAppSidebarAttached = false;
        }
    }

    private void removeSidebarViewImmediate() {
        addSidebarHandler.removeCallbacks(addSidebarRunnable);
        if (mAppSidebarAttached) {
            mWindowManager.removeViewImmediate(mAppSidebar);
            mAppSidebarAttached = false;
        }
    }

    public void onLaunchApplication() {
        if (mAppSidebar != null) {
            mAppSidebar.cancelPendingSwipeAction();
        }
    }

    private boolean showMemDisplay() {
        if (!enableMemDisplay) {
            mMemText.setVisibility(View.GONE);
            mMemBar.setVisibility(View.GONE);
            return false;
        }
        mMemText.setVisibility(View.VISIBLE);
        mMemBar.setVisibility(View.VISIBLE);

        updateMemoryStatus();
        return true;
    }

    public void updateMemoryStatus() {
        if (mMemText.getVisibility() == View.GONE
                || mMemBar.getVisibility() == View.GONE) return;

        MemoryInfo memInfo = new MemoryInfo();
        mAm.getMemoryInfo(memInfo);
            int available = (int)(memInfo.availMem / 1048576L);
            int max = (int)(getTotalMemory() / 1048576L);
            mMemText.setText(mContext.getResources().getString(R.string.slim_recents_free_ram,
                    available));
            mMemBar.setMax(max);
            mMemBar.setProgress(available);
            mMemBar.getProgressDrawable().setColorFilter(mMembarcolor == 0x00ffffff
                    ? mContext.getResources().getColor(R.color.recents_membar_color)
                    : mMembarcolor, Mode.MULTIPLY);
            mMemText.setTextColor(mMemtextcolor == 0x00ffffff
                    ? mContext.getResources().getColor(R.color.recents_membar_text_color)
                    : mMemtextcolor);
    }

    public long getTotalMemory() {
        MemoryInfo memInfo = new MemoryInfo();
        mAm.getMemoryInfo(memInfo);
        long totalMem = memInfo.totalMem;
        return totalMem;
    }

    private class CacheMoreCardsLayoutManager extends LinearLayoutManager {
        private Context context;
        private WindowManager mWindowManager;
        private int mExpandMode;

        public CacheMoreCardsLayoutManager(Context context, WindowManager windowManager,
                                           int expandMode) {
            super(context);
            this.context = context;
            this.mWindowManager = windowManager;
            this.mExpandMode = expandMode;
        }

        @Override
        public boolean supportsPredictiveItemAnimations() {
            return true;
        }

        @Override
        protected int getExtraLayoutSpace(RecyclerView.State state) {
            int space = 300;
            switch(mExpandMode) {
                case RecentPanelView.EXPANDED_MODE_DISABLED:
                    space = 300;
                    break;
                case RecentPanelView.EXPANDED_MODE_NEVER:
                case RecentPanelView.EXPANDED_MODE_AUTO:
                    space = 600;
                    break;
                case RecentPanelView.EXPANDED_MODE_ALWAYS:
                    space = getScreenHeight();
                    break;
            }
            return space;
        }

        private int getScreenHeight() {
            Display display = mWindowManager.getDefaultDisplay();
            Point size = new Point();
            display.getSize(size);
            int screenHeight = size.y;
            return screenHeight;
        }
    }

    protected void pinApp(int persistentTaskId) {
        IStatusBarService statusBar = getStatusBarService();
        if (statusBar != null) {
            try {
                statusBar.showScreenPinningRequest(persistentTaskId);
            } catch (RemoteException e) {
               e.printStackTrace();
            }
            hideRecents(false);
        }
    }

    protected static boolean killAppLongClick(Context context,
            String packageName, int persistentTaskId) {
        boolean killed = false;
        if (context.checkCallingOrSelfPermission(
                android.Manifest.permission.FORCE_STOP_PACKAGES)
                == PackageManager.PERMISSION_GRANTED) {
            if (packageName != null) {
                try {
                    ActivityManagerNative.getDefault().forceStopPackage(
                            packageName, UserHandle.USER_CURRENT);
                    killed = true;
                } catch (RemoteException e) {
                   e.printStackTrace();
                }
                if (killed) {
                    ActivityManagerWrapper.getInstance().removeTask(persistentTaskId);
                }
            } else {
                Log.w(TAG, "Tried to kill package NULL");
            }
        } else {
            Log.e(TAG, "Slim recents is missing the permission to force stop packages");
        }
        return killed;
    }

    /*
     * By default, if you open app A, then app B, then app A again (with double tap or
     * from recents panel), pressing BACK button will go back from app A to app B
     * because they will be in the same stack. So i've added the following code that will
     * instead create a new empty activity at each app launch with HOME as background
     * main activity of the stack, thus the BACK button will always go back to HOME.
     * This needs some lines in SystemUI manifest. Btw, atm i'm debated on this, because
     * we can still press the HOME button to go back to home. So let's hang on for now.
    */
    /*public static class ContainerActivity extends Activity {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }
    }

    private static void startContainerActivity(Context context) {
        Intent mainActivity = new Intent(context,
                ContainerActivity.class);
        mainActivity.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(mainActivity);
    }*/

    private class ComponentCallback implements ComponentCallbacks2 {
        @Override
        public void onTrimMemory(int level) {
            switch (level) {
                case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                    // Stop the loader immediately when the UI is no longer visible
                    cancelPreloadRecentApps();
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                 break;
                case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                    trimCaches(false);
                    break;
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                    // We are going to be low on memory
                    trimCaches(true);
                   break;
                case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                    // We are low on memory, so release everything
                    evictAllCaches();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onLowMemory() {
            // onTrimMemory(TRIM_MEMORY_COMPLETE);
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
        }
    }

    private View.OnLongClickListener mMemBarLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            if (!mMemBarLongClickToClear) {
                return false;
            }
            if (mRecentPanelView.hasClearableTasks()) {
                if (mRecentPanelView.removeAllApplications()) {
                    hideRecents(false);
                }
                return true;
            }
            return false;
        }
    };

    public void onStartExpandAnimation() {
        mExpandAnimation = true;
    }

    private DefaultItemAnimator mItemAnimator = new DefaultItemAnimator() {
        @Override
        public boolean canReuseUpdatedViewHolder(RecyclerView.ViewHolder viewHolder) {
            // Similar to SimpleItemAnimator.setSupportsChangeAnimations(mExpandAnimation)
            if (mExpandAnimation) {
                mExpandAnimation = false;
                return super.canReuseUpdatedViewHolder(viewHolder);
            }
            // Returning true means we don't support change animations here
            return true;
        }
    };

    public void setMediaPlaying(boolean playing, String packageName) {
        mRecentPanelView.setMediaPlaying(playing, packageName);
    }

    public void setMedia(boolean colorizedMedia, int[] colors, Drawable artwork, MediaMetadata mediaMetaData, String title, String text) {
        mRecentPanelView.setMedia(colorizedMedia ? colors[0] : -1,
                colorizedMedia ? ImageHelper.getResizedIconDrawable(
                artwork, mContext, R.dimen.recent_app_icon_size, mScaleFactor) : null,
                mediaMetaData, title, text);
    }
}

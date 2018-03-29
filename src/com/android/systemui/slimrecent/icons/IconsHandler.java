/*
 * Copyright (C) 2017 Paranoid Android
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

package com.android.systemui.slimrecent.icons;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.slimrecent.CacheController;
import com.android.systemui.slimrecent.icons.RecentPanelIcons;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class IconsHandler {

    private static final String TAG = "IconsHandler";

    private Map<String, String> mAppFilterDrawables = new HashMap<>();
    private List<Bitmap> mBackImages = new ArrayList<>();
    private List<String> mDrawables = new ArrayList<>();

    private Bitmap mFrontImage;
    private Bitmap mMaskImage;

    private Resources mCurrentIconPackRes;
    private Resources mOriginalIconPackRes;
    private String mIconPackPackageName = "";

    private Context mContext;
    private PackageManager mPackageManager;

    private float mFactor = 1.0f;

    private static IconsHandler sInstance;

    public static IconsHandler getInstance(Context context) {
        if (sInstance == null){
            sInstance = new IconsHandler();
        }
        sInstance.setContext(context);
        return sInstance;
    }

    public IconsHandler() {
    }

    private void setContext(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    private void loadIconPack(String packageName, boolean fallback) {
        mIconPackPackageName = packageName;
        if (!fallback) {
            mAppFilterDrawables.clear();
            mBackImages.clear();
        } else {
            mDrawables.clear();
        }

        XmlPullParser xpp = null;

        try {
            mOriginalIconPackRes = mPackageManager.getResourcesForApplication(mIconPackPackageName);
            mCurrentIconPackRes = mOriginalIconPackRes;
            int appfilterid = mOriginalIconPackRes.getIdentifier("appfilter", "xml", mIconPackPackageName);
            if (appfilterid > 0) {
                xpp = mOriginalIconPackRes.getXml(appfilterid);
            }

            if (xpp != null) {
                int eventType = xpp.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        if (!fallback & xpp.getName().equals("iconback")) {
                            for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                if (xpp.getAttributeName(i).startsWith("img")) {
                                    String drawableName = xpp.getAttributeValue(i);
                                    Bitmap iconback = loadBitmap(drawableName);
                                    if (iconback != null) {
                                        mBackImages.add(iconback);
                                    }
                                }
                            }
                        } else if (!fallback && xpp.getName().equals("iconmask")) {
                            if (xpp.getAttributeCount() > 0 && xpp.getAttributeName(0).equals("img1")) {
                                String drawableName = xpp.getAttributeValue(0);
                                mMaskImage = loadBitmap(drawableName);
                            }
                        } else if (!fallback && xpp.getName().equals("iconupon")) {
                            if (xpp.getAttributeCount() > 0 && xpp.getAttributeName(0).equals("img1")) {
                                String drawableName = xpp.getAttributeValue(0);
                                mFrontImage = loadBitmap(drawableName);
                            }
                        } else if (!fallback && xpp.getName().equals("scale")) {
                            if (xpp.getAttributeCount() > 0 && xpp.getAttributeName(0).equals("factor")) {
                                mFactor = Float.valueOf(xpp.getAttributeValue(0));
                            }
                        }
                        if (xpp.getName().equals("item")) {
                            String componentName = null;
                            String drawableName = null;

                            for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                if (xpp.getAttributeName(i).equals("component")) {
                                    componentName = xpp.getAttributeValue(i);
                                } else if (xpp.getAttributeName(i).equals("drawable")) {
                                    drawableName = xpp.getAttributeValue(i);
                                }
                            }
                            if (fallback && getIdentifier(packageName, drawableName, true) > 0
                                    && !mDrawables.contains(drawableName)) {
                                mDrawables.add(drawableName);
                            }
                            if (!fallback && componentName != null && drawableName != null &&
                                    !mAppFilterDrawables.containsKey(componentName)) {
                                mAppFilterDrawables.put(componentName, drawableName);
                            }
                        }
                    }
                    eventType = xpp.next();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing appfilter.xml " + e);
        }
    }

    public Drawable getIconFromHandler(Context context, ActivityInfo info, float scaleFactor, int iconSizeId) {
    ComponentName name = new ComponentName(info.applicationInfo.packageName, info.name);
        Bitmap bm = getDrawableIconForPackage(name, scaleFactor, iconSizeId);
        if (bm == null) {
            return null;
        }
        return new BitmapDrawable(context.getResources(), RecentPanelIcons.createIconBitmap(bm, context, scaleFactor, iconSizeId));
    }

    public boolean isDefaultIconPack() {
        return mIconPackPackageName.equalsIgnoreCase("");
    }

    private int getIdentifier(String packageName, String drawableName, boolean currentIconPack) {
        if (drawableName == null) {
            return 0;
        }
        if (packageName == null) {
            packageName = mIconPackPackageName;
        }
        return (!currentIconPack ? mOriginalIconPackRes : mCurrentIconPackRes).getIdentifier(
                drawableName, "drawable", packageName);
    }

    public Drawable loadDrawable(String packageName, String drawableName, boolean currentIconPack) {
        if (packageName == null) {
            packageName = mIconPackPackageName;
        }
        int id = getIdentifier(packageName, drawableName, currentIconPack);
        if (id > 0) {
            return (!currentIconPack ? mOriginalIconPackRes : mCurrentIconPackRes).getDrawable(id, mContext.getTheme());
        }
        return null;
    }

    private Bitmap loadBitmap(String drawableName) {
        Drawable bitmap = loadDrawable(null, drawableName, true);
        if (bitmap != null && bitmap instanceof BitmapDrawable) {
            return ((BitmapDrawable) bitmap).getBitmap();
        }
        return null;
    }

    private Bitmap getDefaultAppDrawable(ComponentName componentName, float scaleFactor, int iconSizeId) {
        Drawable drawable = null;
        try {
            drawable = mPackageManager.getApplicationIcon(mPackageManager.getApplicationInfo(
                    componentName.getPackageName(), 0));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Unable to find component " + componentName.toString() + e);
        }
        if (drawable == null) {
            return null;
        }

        return generateBitmap(componentName, RecentPanelIcons.createIconBitmap(drawable, mContext, scaleFactor, iconSizeId), scaleFactor, iconSizeId);
    }

    public Bitmap getDrawableIconForPackage(ComponentName componentName, float scaleFactor, int iconSizeId) {

        String drawableName = mAppFilterDrawables.get(componentName.toString());
        Drawable drawable = loadDrawable(null, drawableName, false);
        if (drawable != null && drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            return bitmap;
        }

        return getDefaultAppDrawable(componentName, scaleFactor, iconSizeId);
    }

    private Bitmap generateBitmap(ComponentName componentName, Bitmap defaultBitmap, float scaleFactor, int iconSizeId) {
        Drawable d = new BitmapDrawable(mContext.getResources(), defaultBitmap);
        if (mBackImages.isEmpty()) {
            return RecentPanelIcons.createBadgedIconBitmap(d, Process.myUserHandle(),
                    mContext, Build.VERSION.SDK_INT, true, scaleFactor, iconSizeId);
        }

        Bitmap wrapped = RecentPanelIcons.createBadgedIconBitmap(d, Process.myUserHandle(),
                mContext, Build.VERSION.SDK_INT, false, scaleFactor, iconSizeId);

        Random random = new Random();
        int id = random.nextInt(mBackImages.size());
        Bitmap backImage = mBackImages.get(id);
        int w = backImage.getWidth();
        int h = backImage.getHeight();

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(backImage, 0, 0, null);

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(wrapped,
                (int) (w * mFactor), (int) (h * mFactor), false);

        Bitmap mutableMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(mutableMask);
        Bitmap targetBitmap = mMaskImage == null ? mutableMask : mMaskImage;
        maskCanvas.drawBitmap(targetBitmap, 0, 0, new Paint());

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST));
        canvas.drawBitmap(scaledBitmap, (w - scaledBitmap.getWidth()) / 2,
                (h - scaledBitmap.getHeight()) / 2, null);
        canvas.drawBitmap(mutableMask, 0, 0, paint);

        if (mFrontImage != null) {
            canvas.drawBitmap(mFrontImage, 0, 0, null);
        }
        return result;
    }

    public void updatePrefs(String iconPack) {
        if (iconPack == null || iconPack.equals(mIconPackPackageName)) {
            return;
        }
        mIconPackPackageName = iconPack;
        if (!TextUtils.isEmpty(iconPack) || TextUtils.isEmpty(mIconPackPackageName)) {
            CacheController.getInstance(mContext, null).clearCache();
            mAppFilterDrawables.clear();
            mBackImages.clear();
            mDrawables.clear();
            mCurrentIconPackRes = null;
            mOriginalIconPackRes = null;
            resetIconNormalizer();
        }
        if (!TextUtils.isEmpty(mIconPackPackageName)) {
            loadIconPack(iconPack, false);
        }
    }

    public void resetIconNormalizer() {
        //TODO: we need different instances for different Recents mode, so the Singleton class is
        // not really usefull, a normal class would be good
        IconNormalizer.removeInstance();
        ShadowGenerator.removeInstance();
    }
}

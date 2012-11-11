/*==============================================================================
            Copyright (c) 2012 QUALCOMM Austria Research Center GmbH.
            All Rights Reserved.
            Qualcomm Confidential and Proprietary
            
@file 
    VirtualButtons.java

@brief
    Sample for VirtualButtons

==============================================================================*/


package com.qualcomm.QCARSamples.VirtualButtons;

import java.util.Vector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;

import com.qualcomm.QCAR.QCAR;
import cz.marax.virtualpiano.R;


/** The main activity for the VirtualButtons sample. */
public class VirtualButtons extends Activity
{
    // Menu constants:
    private static final int MENU_RED = 0;
    private static final int MENU_BLUE = 1;
    private static final int MENU_YELLOW = 2;
    private static final int MENU_GREEN = 3;
    private static final int MENU_FOCUS_FLASH = 4;
    private static final int MENU_TOGGLE_FLASH = 5;
    private static final int MENU_AUTOFOCUS = 6;
    private int nic;
    // Application status constants:
    private static final int APPSTATUS_UNINITED         = -1;
    private static final int APPSTATUS_INIT_APP         = 0;
    private static final int APPSTATUS_INIT_QCAR        = 1;
    private static final int APPSTATUS_INIT_TRACKER     = 2;    
    private static final int APPSTATUS_INIT_APP_AR      = 3;
    private static final int APPSTATUS_LOAD_TRACKER     = 4;
    private static final int APPSTATUS_INITED           = 5;
    private static final int APPSTATUS_CAMERA_STOPPED   = 6;
    private static final int APPSTATUS_CAMERA_RUNNING   = 7;
    
    // Name of the native dynamic libraries to load:
    private static final String NATIVE_LIB_SAMPLE = "VirtualButtons";
    private static final String NATIVE_LIB_QCAR = "QCAR";

    // Our OpenGL view:
    private QCARSampleGLView mGlView;
    
    // The view to display the sample splash screen:
    private ImageView mSplashScreenView;
    
    // The handler and runnable for the splash screen time out task.
    private Handler mSplashScreenHandler;
    private Runnable mSplashScreenRunnable;
    
    // The minimum time the splash screen should be visible:
    private static final long MIN_SPLASH_SCREEN_TIME = 2000;    
    
    // The time when the splash screen has become visible:
    long mSplashScreenStartTime = 0;
    
    // Our renderer:
    private VirtualButtonsRenderer mRenderer;
    
    // Display size of the device
    private int mScreenWidth = 0;
    private int mScreenHeight = 0;
    
    // The current application status
    private int mAppStatus = APPSTATUS_UNINITED;
    
    // The async tasks to initialize the QCAR SDK 
    private InitQCARTask mInitQCARTask;
    private LoadTrackerTask mLoadTrackerTask;

    // An object used for synchronizing QCAR initialization, dataset loading and
    // the Android onDestroy() life cycle event. If the application is destroyed
    // while a data set is still being loaded, then we wait for the loading
    // operation to finish before shutting down QCAR.
    private Object mShutdownLock = new Object();
    
    // QCAR initialization flags
    private int mQCARFlags = 0;
    
    // The textures we will use for rendering:
    private Vector<Texture> mTextures;
    private int mSplashScreenImageResource = 0;

    
    /** Static initializer block to load native libraries on start-up. */
    static
    {
        loadLibrary(NATIVE_LIB_QCAR);
        loadLibrary(NATIVE_LIB_SAMPLE);
    }
    
    
    /** An async task to initialize QCAR asynchronously. */
    private class InitQCARTask extends AsyncTask<Void, Integer, Boolean>
    {   
        // Initialize with invalid value
        private int mProgressValue = -1;
        
        protected Boolean doInBackground(Void... params)
        {
            // Prevent the onDestroy() method to overlap with initialization:
            synchronized (mShutdownLock)
            {
                QCAR.setInitParameters(VirtualButtons.this, mQCARFlags);
                
                do
                {
                    // QCAR.init() blocks until an initialization step is complete,
                    // then it proceeds to the next step and reports progress in
                    // percents (0 ... 100%)
                    // If QCAR.init() returns -1, it indicates an error.
                    // Initialization is done when progress has reached 100%.
                    mProgressValue = QCAR.init();
                    
                    // Publish the progress value:
                    publishProgress(mProgressValue);
                    
                    // We check whether the task has been canceled in the meantime
                    // (by calling AsyncTask.cancel(true))
                    // and bail out if it has, thus stopping this thread.
                    // This is necessary as the AsyncTask will run to completion
                    // regardless of the status of the component that started is.
                } while (!isCancelled() && mProgressValue >= 0 && mProgressValue < 100);
                
                return (mProgressValue > 0);   
            }
        }

        
        protected void onProgressUpdate(Integer... values)
        {
            // Do something with the progress value "values[0]", e.g. update
            // splash screen, progress bar, etc.
        }

        
        protected void onPostExecute(Boolean result)
        {
            // Done initializing QCAR, proceed to next application
            // initialization status:
            if (result)
            {
                DebugLog.LOGD("InitQCARTask::onPostExecute: QCAR initialization" +
                                                            " successful");

                updateApplicationStatus(APPSTATUS_INIT_TRACKER);
            }
            else
            {
                // Create dialog box for display error:
                AlertDialog dialogError = new AlertDialog.Builder(VirtualButtons.this).create();
                dialogError.setButton(
                    "Close",
                    new DialogInterface.OnClickListener()
                    {
                        public void onClick(DialogInterface dialog, int which)
                        {
                            // Exiting application
                            System.exit(1);
                        }
                    }
                ); 
                
                String logMessage;

                // NOTE: Check if initialization failed because the device is
                // not supported. At this point the user should be informed
                // with a message.
                if (mProgressValue == QCAR.INIT_DEVICE_NOT_SUPPORTED)
                {
                    logMessage = "Failed to initialize QCAR because this " +
                        "device is not supported.";
                }
                else
                {
                    logMessage = "Failed to initialize QCAR.";
                }
                
                // Log error:
                DebugLog.LOGE("InitQCARTask::onPostExecute: " + logMessage +
                                " Exiting.");
                
                // Show dialog box with error message:
                dialogError.setMessage(logMessage);  
                dialogError.show();
            }
        }
    }
    
    
    /** An async task to load the tracker data asynchronously. */
    private class LoadTrackerTask extends AsyncTask<Void, Integer, Boolean>
    {
        protected Boolean doInBackground(Void... params)
        {
            // Prevent the onDestroy() method to overlap:
            synchronized (mShutdownLock)
            {
                // Load the tracker data set:
                return (loadTrackerData() > 0);
            }
        }
        
        protected void onPostExecute(Boolean result)
        {
            DebugLog.LOGD("LoadTrackerTask::onPostExecute: execution " +
                        (result ? "successful" : "failed"));

            if (result)
            {               
                // Done loading the tracker, update application status: 
                updateApplicationStatus(APPSTATUS_INITED);
            }
            else
            {
                // Create dialog box for display error:
                AlertDialog dialogError = new AlertDialog.Builder(VirtualButtons.this).create();
                dialogError.setButton(
                    "Close",
                    new DialogInterface.OnClickListener()
                    {
                        public void onClick(DialogInterface dialog, int which)
                        {
                            // Exiting application
                            System.exit(1);
                        }
                    }
                ); 
                
                // Show dialog box with error message:
                dialogError.setMessage("Failed to load tracker data.");  
                dialogError.show();
            }
        }        
    }
    
    private void storeScreenDimensions()
    {
        // Query display dimensions
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
    }

    /** Called when the activity first starts or the user navigates back
     * to an activity. */
    protected void onCreate(Bundle savedInstanceState)
    {
        DebugLog.LOGD("VirtualButtons::onCreate");
        super.onCreate(savedInstanceState);
        
        // Set the splash screen image to display during initialization:
        mSplashScreenImageResource = R.drawable.splash_screen_virtual_buttons;
        
        // Load any sample specific textures:  
        mTextures = new Vector<Texture>();
        loadTextures();
        
        // Query the QCAR initialization flags:
        mQCARFlags = getInitializationFlags();
        
        // Update the application status to start initializing application
        updateApplicationStatus(APPSTATUS_INIT_APP);
    }

    
    /** We want to load specific textures from the APK, which we will later
    use for rendering. */
    private void loadTextures()
    {
        mTextures.add(Texture.loadTextureFromApk("TextureTeapotBrass.png",
                getAssets()));
        mTextures.add(Texture.loadTextureFromApk("TextureTeapotRed.png",
                getAssets()));
        mTextures.add(Texture.loadTextureFromApk("TextureTeapotBlue.png",
                getAssets()));
        mTextures.add(Texture.loadTextureFromApk("TextureTeapotYellow.png",
                getAssets()));        
        mTextures.add(Texture.loadTextureFromApk("TextureTeapotGreen.png",
                getAssets()));
    }

    
    /** Configure QCAR with the desired version of OpenGL ES. */
    private int getInitializationFlags()
    {
        return QCAR.GL_20;
    }    
    
    /** Native tracker initialization and deinitialization. */
    public native int initTracker();
    public native void deinitTracker();

    /** Native functions to load and destroy tracking data. */
    public native int loadTrackerData();
    public native void destroyTrackerData();    
    
    /** Native methods for starting and stoping the camera. */ 
    private native void startCamera();
    private native void stopCamera();

    /** Native method for setting / updating the projection matrix for AR content rendering */
    private native void setProjectionMatrix();


   /** Called when the activity will start interacting with the user.*/
    protected void onResume()
    {
        DebugLog.LOGD("VirtualButtons::onResume");
        super.onResume();
        
        // QCAR-specific resume operation
        QCAR.onResume();
        
        // We may start the camera only if the QCAR SDK has already been 
        // initialized
        if (mAppStatus == APPSTATUS_CAMERA_STOPPED)
        {
            updateApplicationStatus(APPSTATUS_CAMERA_RUNNING);
            
            // Reactivate flash if it was active before pausing the app
            if (mFlash)
            {
                boolean result = activateFlash(mFlash);
                DebugLog.LOGI("Turning flash "+(mFlash?"ON":"OFF")+" "+(result?"WORKED":"FAILED")+"!!");
            }
        }
        
        // Resume the GL view:
        if (mGlView != null)
        {
            mGlView.setVisibility(View.VISIBLE);
            mGlView.onResume();
        }        
    }
    
    public void onConfigurationChanged(Configuration config)
    {
        DebugLog.LOGD("VirtualButtons::onConfigurationChanged");
        super.onConfigurationChanged(config);
        
        storeScreenDimensions();
        
        // Set projection matrix:
        if (QCAR.isInitialized())
            setProjectionMatrix();
    }    

    /** Called when the system is about to start resuming a previous activity.*/
    protected void onPause()
    {
        DebugLog.LOGD("VirtualButtons::onPause");
        super.onPause();
        
        if (mGlView != null)
        {
            mGlView.setVisibility(View.INVISIBLE);
            mGlView.onPause();
        }
        
        if (mAppStatus == APPSTATUS_CAMERA_RUNNING)
        {
            updateApplicationStatus(APPSTATUS_CAMERA_STOPPED);
        }
        
        // QCAR-specific pause operation
        QCAR.onPause();
    }
    
    
    /** Native function to deinitialize the application.*/
    private native void deinitApplicationNative();

    
    /** The final call you receive before your activity is destroyed.*/
    protected void onDestroy()
    {
        DebugLog.LOGD("VirtualButtons::onDestroy");
        super.onDestroy();
        
        // Dismiss the splash screen time out handler:
        if (mSplashScreenHandler != null)
        {
            mSplashScreenHandler.removeCallbacks(mSplashScreenRunnable);
            mSplashScreenRunnable = null;
            mSplashScreenHandler = null;
        }
        
        // Cancel potentially running tasks
        if (mInitQCARTask != null &&
            mInitQCARTask.getStatus() != InitQCARTask.Status.FINISHED)
        {
            mInitQCARTask.cancel(true);
            mInitQCARTask = null;
        }
        
        if (mLoadTrackerTask != null &&
            mLoadTrackerTask.getStatus() != LoadTrackerTask.Status.FINISHED)
        {
            mLoadTrackerTask.cancel(true);
            mLoadTrackerTask = null;
        }
        
        // Ensure that all asynchronous operations to initialize QCAR and loading
        // the tracker datasets do not overlap:
        synchronized (mShutdownLock) {
            
            // Do application deinitialization in native code
            deinitApplicationNative();
            
            // Unload texture
            mTextures.clear();
            mTextures = null;
            
            // Destroy the tracking data set:
            destroyTrackerData();
            
            // Deinit the tracker:
            deinitTracker();
            
            // Deinitialize QCAR SDK
            QCAR.deinit();   
        }
        
        System.gc();
    }

    
    /** Invoked the first time when the options menu is displayed to give
     *  the Activity a chance to populate its Menu with menu items. */
    public boolean onCreateOptionsMenu(Menu menu)
    {
        DebugLog.LOGD("VirtualButtons::onCreateOptionsMenu");
        super.onCreateOptionsMenu(menu);

        // create menus with icons
        //
        menu.add(0, MENU_RED, 0, R.string.menu_red)
            .setIcon(R.drawable.menu_red);
        menu.add(0, MENU_BLUE, 0, R.string.menu_blue)
            .setIcon(R.drawable.menu_blue);
        menu.add(0, MENU_YELLOW, 0, R.string.menu_yellow)
            .setIcon(R.drawable.menu_yellow);
        menu.add(0, MENU_GREEN, 0, R.string.menu_green)
            .setIcon(R.drawable.menu_green);
        
        SubMenu focusAndFlash = menu.addSubMenu(0, MENU_FOCUS_FLASH, 0,
                                                "Focus/Flash");
        focusAndFlash.add(0, MENU_TOGGLE_FLASH, 0, "Toggle flash");
        focusAndFlash.add(0, MENU_AUTOFOCUS, 0, "Trigger autofocus");

        return true;
    }


    /** Native function to create/destroy a Virtual Button.
     *  Existing buttons will be destroyed and non-existing will be created. */
    private native void addButtonToToggle(int virtualButtonIdx);

    /** Invoked when the user selects an item from the Menu */
    public boolean onOptionsItemSelected(MenuItem item)
    {
        DebugLog.LOGD("VirtualButtons::onOptionsItemSelected " +
                        item.getItemId());
        
        // This flag gets only set to false if no item is handled or handline
        // failed
        boolean itemHandled = true;

        // Handle menu items
        switch (item.getItemId())
        {
            case MENU_RED:
                addButtonToToggle(0);
                break;

            case MENU_BLUE:
                addButtonToToggle(1);
                break;

            case MENU_YELLOW:
                addButtonToToggle(2);
                break;

            case MENU_GREEN:
                addButtonToToggle(3);
                break;

            case MENU_TOGGLE_FLASH:
                mFlash = !mFlash;
                itemHandled = activateFlash(mFlash);
                DebugLog.LOGI("Turning flash " + (mFlash?"ON":"OFF") + " " + 
                                (itemHandled?"WORKED":"FAILED") + "!!");
                break;
            
            case MENU_AUTOFOCUS:
                itemHandled = autofocus();
                DebugLog.LOGI("Autofocus requested" +
                        (itemHandled ? " successfully." :
                        ".  Not supported in current mode or on this device."));
                break;
                
            default:
                itemHandled = false;
                break;
        }

        return itemHandled;
    }
    
    private MenuItem checked;
    private boolean mFlash = false;
    private native boolean activateFlash(boolean flash);
    private native boolean autofocus();

    
    /** NOTE: this method is synchronized because of a potential concurrent
     * access by VirtualButtons::onResume() and InitQCARTask::onPostExecute(). */
    private synchronized void updateApplicationStatus(int appStatus)
    {
        // Exit if there is no change in status
        if (mAppStatus == appStatus)
            return;

        // Store new status value      
        mAppStatus = appStatus;

        // Execute application state-specific actions
        switch (mAppStatus)
        {
            case APPSTATUS_INIT_APP:
                // Initialize application elements that do not rely on QCAR
                // initialization  
                initApplication();
                
                // Proceed to next application initialization status
                updateApplicationStatus(APPSTATUS_INIT_QCAR);
                break;

            case APPSTATUS_INIT_QCAR:
                // Initialize QCAR SDK asynchronously to avoid blocking the
                // main (UI) thread.
                // This task instance must be created and invoked on the UI
                // thread and it can be executed only once!
                try
                {
                    mInitQCARTask = new InitQCARTask();
                    mInitQCARTask.execute();
                }
                catch (Exception e)
                {
                    DebugLog.LOGE("Initializing QCAR SDK failed");
                }
                break;
                
            case APPSTATUS_INIT_TRACKER:
                // Initialize the ImageTracker
                if (initTracker() > 0)
                {
                    // Proceed to next application initialization status
                    updateApplicationStatus(APPSTATUS_INIT_APP_AR);     
                }
                break;
                
            case APPSTATUS_INIT_APP_AR:
                // Initialize Augmented Reality-specific application elements
                // that may rely on the fact that the QCAR SDK has been
                // already initialized
                initApplicationAR();
                
                // Proceed to next application initialization status
                updateApplicationStatus(APPSTATUS_LOAD_TRACKER);
                break;
                
            case APPSTATUS_LOAD_TRACKER:
                // Load the tracking data set
                //
                // This task instance must be created and invoked on the UI
                // thread and it can be executed only once!
                try
                {
                    mLoadTrackerTask = new LoadTrackerTask();
                    mLoadTrackerTask.execute();
                }
                catch (Exception e)
                {
                    DebugLog.LOGE("Loading tracking data set failed");
                }
                break;
                
            case APPSTATUS_INITED:
                // Hint to the virtual machine that it would be a good time to
                // run the garbage collector.
                //
                // NOTE: This is only a hint. There is no guarantee that the
                // garbage collector will actually be run.
                System.gc();
                
                // The elapsed time since the splash screen was visible:
                long splashScreenTime = System.currentTimeMillis() - 
                                            mSplashScreenStartTime;
                long newSplashScreenTime = 0;
                if (splashScreenTime < MIN_SPLASH_SCREEN_TIME)
                {
                    newSplashScreenTime = MIN_SPLASH_SCREEN_TIME -
                                            splashScreenTime;   
                }
                
                // Request a callback function after a given timeout to dismiss
                // the splash screen:
                mSplashScreenHandler = new Handler();
                mSplashScreenRunnable =
                    new Runnable() {
                        public void run()
                        {
                            // Hide the splash screen
                            mSplashScreenView.setVisibility(View.INVISIBLE);
                            
                            // Activate the renderer
                            mRenderer.mIsActive = true;
    
                            // Now add the GL surface view. It is important
                            // that the OpenGL ES surface view gets added
                            // BEFORE the camera is started and video
                            // background is configured.
                            addContentView(mGlView, new LayoutParams(
                                            LayoutParams.FILL_PARENT,
                                            LayoutParams.FILL_PARENT));
                            
                            // Start the camera:
                            updateApplicationStatus(APPSTATUS_CAMERA_RUNNING);
                        }
                };

                mSplashScreenHandler.postDelayed(mSplashScreenRunnable,
                                                    newSplashScreenTime); 
                break;
                
            case APPSTATUS_CAMERA_STOPPED:
                // Call the native function to stop the camera
                stopCamera();
                break;
                
            case APPSTATUS_CAMERA_RUNNING:
                // Call the native function to start the camera
                startCamera(); 
                setProjectionMatrix();
                break;
                
            default:
                throw new RuntimeException("Invalid application state");
        }
    }
    
    
    /** Tells native code whether we are in portait or landscape mode */
    private native void setActivityPortraitMode(boolean isPortrait);
    
    
    /** Initialize application GUI elements that are not related to AR. */
    private void initApplication()
    {
        // Set the screen orientation
        //
        // NOTE: It is recommended to set this because of the following reasons:
        //
        //    1.) Before Android 2.2 there is no reliable way to query the
        //        absolute screen orientation from an activity, therefore using 
        //        an undefined orientation is not recommended. Screen 
        //        orientation matching orientation sensor measurements is also
        //        not recommended as every screen orientation change triggers
        //        deinitialization / (re)initialization steps in internal QCAR 
        //        SDK components resulting in unnecessary overhead during 
        //        application run-time.
        //
        //    2.) Android camera drivers seem to always deliver landscape images
        //        thus QCAR SDK components (e.g. camera capturing) need to know 
        //        when we are in portrait mode. Before Android 2.2 there is no 
        //        standard, device-independent way to let the camera driver know 
        //        that we are in portrait mode as each device seems to require a
        //        different combination of settings to rotate camera preview 
        //        frames images to match portrait mode views. Because of this,
        //        we suggest that the activity using the QCAR SDK be locked
        //        to landscape mode if you plan to support Android 2.1 devices
        //        as well. Froyo is fine with both orientations.
        int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        
        // Apply screen orientation
        setRequestedOrientation(screenOrientation);
        
        // Pass on screen orientation info to native code
        setActivityPortraitMode(screenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        storeScreenDimensions();        

        // As long as this window is visible to the user, keep the device's
        // screen turned on and bright.
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
              
        // Create and add the splash screen view
        mSplashScreenView = new ImageView(this);
        mSplashScreenView.setImageResource(mSplashScreenImageResource);
        addContentView(mSplashScreenView, new LayoutParams(
                        LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
        
        mSplashScreenStartTime = System.currentTimeMillis();

    }
    
    
    /** Native function to initialize the application. */
    private native void initApplicationNative(int width, int height);


    /** Initializes AR application components. */
    private void initApplicationAR()
    {        
        // Do application initialization in native code (e.g. registering
        // callbacks, etc.)
        initApplicationNative(mScreenWidth, mScreenHeight);

        // Create OpenGL ES view:
        int depthSize = 16;
        int stencilSize = 0;
        boolean translucent = QCAR.requiresAlpha();
        
        mGlView = new QCARSampleGLView(this);
        mGlView.init(mQCARFlags, translucent, depthSize, stencilSize);
        
        mRenderer = new VirtualButtonsRenderer();
        mGlView.setRenderer(mRenderer);
 
    }

    
    /** Returns the number of registered textures. */
    public int getTextureCount()
    {
        return mTextures.size();
    }

    
    /** Returns the texture object at the specified index. */
    public Texture getTexture(int i)
    {
        return mTextures.elementAt(i);
    }

    
    /** A helper for loading native libraries stored in "libs/armeabi*". */
    public static boolean loadLibrary(String nLibName)
    {
        try
        {
            System.loadLibrary(nLibName);
            DebugLog.LOGI("Native library lib" + nLibName + ".so loaded");
            return true;
        }
        catch (UnsatisfiedLinkError ulee)
        {
            DebugLog.LOGE("The library lib" + nLibName +
                            ".so could not be loaded");
        }
        catch (SecurityException se)
        {
            DebugLog.LOGE("The library lib" + nLibName +
                            ".so was not allowed to be loaded");
        }
        
        return false;
    }    
}


package nl.tudelft.followbot;

import java.util.Observable;
import java.util.Observer;

import nl.tudelft.followbot.calibration.AccelerometerCalibration;
import nl.tudelft.followbot.camera.CameraEstimator;
import nl.tudelft.followbot.data.DataStack;
import nl.tudelft.followbot.data.FeatureExtractor;
import nl.tudelft.followbot.knn.FeatureVector;
import nl.tudelft.followbot.knn.KNN;
import nl.tudelft.followbot.knn.KNNClass;
import nl.tudelft.followbot.sensors.Accelerometer;
import nl.tudelft.followbot.sensors.SensorSink;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import android.app.Activity;
import android.content.Context;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

public class MainActivity extends Activity implements CvCameraViewListener2 {

	private static final int VIEW_MODE_RGBA = 0;
	private static final int VIEW_MODE_THRESH = 2;
	private static final int VIEW_MODE_OD_RGBA = 5;

	private static final int THRESH_GREEN_HMIN = 30;
	private static final int THRESH_GREEN_SMIN = 50;
	private static final int THRESH_GREEN_VMIN = 50;

	private static final int THRESH_GREEN_HMAX = 50;
	private static final int THRESH_GREEN_SMAX = 255;
	private static final int THRESH_GREEN_VMAX = 255;

	private static final int THRESH_BLUE_HMIN = 0;
	private static final int THRESH_BLUE_SMIN = 50;
	private static final int THRESH_BLUE_VMIN = 50;

	private static final int THRESH_BLUE_HMAX = 15;
	private static final int THRESH_BLUE_SMAX = 255;
	private static final int THRESH_BLUE_VMAX = 255;

	private int mViewMode;
	private Mat mRgba;
	private Mat mGray;

	private MenuItem mItemPreviewRGBA;
	private MenuItem mItemPreviewThresh;
	private MenuItem mItemPreviewOdRGBA;

	private CameraBridgeViewBase mOpenCvCameraView;

	private final CameraEstimator positionEstimation = new CameraEstimator();

	private Accelerometer accel;

	private FeatureVector standFeature;
	private FeatureVector walkFeature;
	private final KNNClass standClass = new KNNClass("stand");
	private final KNNClass walkClass = new KNNClass("walk");
	private final KNN knn = new KNN();

	private final String TAG = "FollowBot";

	private final DataStack<float[]> accelStack = new DataStack<float[]>(512);

	private final BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(
			this) {
		@Override
		public void onManagerConnected(int status) {
			switch (status) {
			case LoaderCallbackInterface.SUCCESS: {
				// Log.i(TAG, "OpenCV loaded successfully");

				// Load native library after(!) OpenCV initialization
				System.loadLibrary("object_tracking");

				mOpenCvCameraView.enableView();
			}
				break;
			default: {
				super.onManagerConnected(status);
			}
				break;
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		setContentView(R.layout.activity_main);

		accel = new Accelerometer(
				(SensorManager) getSystemService(Context.SENSOR_SERVICE));

		accel.addListener(new SensorSink() {
			@Override
			public void push(SensorEvent event) {
				accelStack.push(new float[] { event.timestamp, event.values[0],
						event.values[1], event.values[2] });
			}
		});

		// open new camera view
		mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.surface_view);
		mOpenCvCameraView.setMaxFrameSize(480, 360);
		mOpenCvCameraView.setCvCameraViewListener(this);
	}

	@Override
	protected void onPause() {
		super.onPause();

		if (mOpenCvCameraView != null)
			mOpenCvCameraView.disableView();

		if (accel != null) {
			accel.pause();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_3, this,
				mLoaderCallback);

		if (accel != null) {
			accel.resume();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		if (mOpenCvCameraView != null) {
			mOpenCvCameraView.disableView();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);

		mItemPreviewRGBA = menu.add("Preview RGBA");
		mItemPreviewThresh = menu.add("Object Detection Threshold");
		mItemPreviewOdRGBA = menu.add("Object Detection RGBA");

		return true;
	}

	@Override
	public void onCameraViewStarted(int width, int height) {
		mRgba = new Mat(height, width, CvType.CV_8UC4);
		mGray = new Mat(height, width, CvType.CV_8UC1);
	}

	@Override
	public void onCameraViewStopped() {
		mRgba.release();
		mGray.release();
	}

	@Override
	public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
		final int viewMode = mViewMode;

		switch (viewMode) {
		case VIEW_MODE_RGBA:
			// input frame has RBGA format
			mRgba = inputFrame.rgba();
			break;
		case VIEW_MODE_THRESH:
			// input frame has RGBA format
			mRgba = inputFrame.rgba();
			mGray = inputFrame.gray();

			positionEstimation.CircleObjectTrack(THRESH_GREEN_HMIN,
					THRESH_GREEN_SMIN, THRESH_GREEN_VMIN, THRESH_GREEN_HMAX,
					THRESH_GREEN_SMAX, THRESH_GREEN_VMAX, THRESH_BLUE_HMIN,
					THRESH_BLUE_SMIN, THRESH_BLUE_VMIN, THRESH_BLUE_HMAX,
					THRESH_BLUE_SMAX, THRESH_BLUE_VMAX, mRgba.width(),
					mRgba.height(), mGray.getNativeObjAddr(),
					mRgba.getNativeObjAddr(), true);

			Log.d("Position", positionEstimation.getAngleSkew() + " "
					+ positionEstimation.getAngleOrientation() + " "
					+ positionEstimation.getTranslationHorizontal() + " "
					+ positionEstimation.getTranslationVertical() + " "
					+ positionEstimation.getDistancePhoneRobot() + " "
					+ positionEstimation.getDistanceUserRobot() + "");

			break;
		case VIEW_MODE_OD_RGBA:
			// input frame has RGBA format
			mRgba = inputFrame.rgba();
			mGray = inputFrame.gray();

			positionEstimation.CircleObjectTrack(THRESH_GREEN_HMIN,
					THRESH_GREEN_SMIN, THRESH_GREEN_VMIN, THRESH_GREEN_HMAX,
					THRESH_GREEN_SMAX, THRESH_GREEN_VMAX, THRESH_BLUE_HMIN,
					THRESH_BLUE_SMIN, THRESH_BLUE_VMIN, THRESH_BLUE_HMAX,
					THRESH_BLUE_SMAX, THRESH_BLUE_VMAX, mRgba.width(),
					mRgba.height(), mGray.getNativeObjAddr(),
					mRgba.getNativeObjAddr(), false);

			Log.d("Position", positionEstimation.getAngleSkew() + " "
					+ positionEstimation.getAngleOrientation() + " "
					+ positionEstimation.getTranslationHorizontal() + " "
					+ positionEstimation.getTranslationVertical() + " "
					+ positionEstimation.getDistancePhoneRobot() + " "
					+ positionEstimation.getDistanceUserRobot() + "");

			break;
		}

		return mRgba;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Log.i(TAG, "called onOptionsItemSelected; selected item: " + item);

		if (item == mItemPreviewRGBA) {
			mViewMode = VIEW_MODE_RGBA;
		} else if (item == mItemPreviewThresh) {
			mViewMode = VIEW_MODE_THRESH;
		} else if (item == mItemPreviewOdRGBA) {
			mViewMode = VIEW_MODE_OD_RGBA;
		}

		return true;
	}

	public void onClickCalStand(View view) {
		Button btn = (Button) view;
		final int calibrationTime = 4;
		final AccelerometerCalibration cal = new AccelerometerCalibration(
				accel, calibrationTime, btn);

		cal.addObserver(new Observer() {
			@Override
			public void update(Observable observable, Object data) {
				standFeature = new FeatureVector(standClass, FeatureExtractor
						.extractFeaturesFromFloat4(cal.getData()));
				knn.add(standFeature);
			}
		});

		cal.start();
	}

	public void onClickCalWalk(View view) {
		Button btn = (Button) view;
		final int calibrationTime = 10;
		final AccelerometerCalibration cal = new AccelerometerCalibration(
				accel, calibrationTime, btn);

		cal.addObserver(new Observer() {
			@Override
			public void update(Observable observable, Object data) {
				walkFeature = new FeatureVector(walkClass, FeatureExtractor
						.extractFeaturesFromFloat4(cal.getData()));
				knn.add(walkFeature);
			}
		});

		cal.start();
	}

	public void onClickDetectActivity(View view) {
		FeatureVector feature = new FeatureVector(null,
				FeatureExtractor.extractFeaturesFromFloat4(accelStack));
		KNNClass klass = knn.classify(feature, 1);
		Log.d(TAG, klass.getName());
	}
}

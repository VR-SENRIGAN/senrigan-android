package senrigan.vr.senrigan_android;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {
  private static final String LOGGER_TAG = "SENRIGAN";
  private static final int MATRIX_SIZE = 16;
  private static final int DIMENSION = 3;
  protected final static double RAD2DEG = 180/Math.PI;
  private float[] mMagneticValues = new float[3];
  private float[] mAccelerometerValues = new float[9];

  private SensorManager manager;
  private TextView values;
  private long prevTime = 0;
  private MqttAndroidClient mqttAndroidClient;
  private IMqttToken conToken;
  private String rCameraURL;
  private String lCameraURL;
  private MjpegView mvl;
  private MjpegView mvr;

  /**
   * Called when the activity is first created.
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.content_main);

    mvl = (MjpegView) findViewById(R.id.mvl);
    mvl.setResolution(160, 120);
    mvr = (MjpegView) findViewById(R.id.mvr);
    mvr.setResolution(160, 120);

    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    MqttConnectOptions options = new MqttConnectOptions();

    mqttAndroidClient = new MqttAndroidClient(this, "url-to-mqtt-server", "piyo");
    try {
      mqttAndroidClient.connect(options, null, new IMqttActionListener() {
        @Override
        public void onSuccess(IMqttToken iMqttToken) {
          Log.d("SENRIGAN", "onSuccess");

          try {
            mqttAndroidClient.setCallback(new MqttCallback() {
              @Override
              public void connectionLost(Throwable cause) {
              }

              @Override
              public void messageArrived(String topic, MqttMessage message) throws Exception {
                System.out.println(message);
                if (topic.equals("left_camera_url")) {
                  //lCameraURL = message.toString();
                  lCameraURL = "url-to-left-camera";
                  new DoRead(mvl).execute(lCameraURL);
                  Log.i(LOGGER_TAG, "Left Camera:" + lCameraURL);
                } else if (topic.equals("right_camera_url")) {
                  //rCameraURL = message.toString();
                  rCameraURL = "url-to-right-camera";
                  new DoRead(mvr).execute(rCameraURL);
                  Log.i(LOGGER_TAG, "Right Camera:" + rCameraURL);
                }
              }

              @Override
              public void deliveryComplete(IMqttDeliveryToken token) { }

            });
            mqttAndroidClient.subscribe("left_camera_url", 0);
            mqttAndroidClient.subscribe("right_camera_url", 0);
          } catch (MqttException e) {
            Log.v(LOGGER_TAG, e.toString());
          }
        }

        @Override
        public void onFailure(IMqttToken iMqttToken, Throwable throwable) {
          Log.d("SENRIGAN", "onFailure");
        }
      });
    } catch (MqttException e) {
      e.printStackTrace();
    }

    values = (TextView) findViewById(R.id.text_view);
    manager = (SensorManager) getSystemService(SENSOR_SERVICE);
  }

  @Override
  protected void onStop() {
    super.onStop();
    manager.unregisterListener(this);
  }

  @Override
  protected void onResume() {
    super.onResume();
    {
      List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_MAGNETIC_FIELD);
      if (sensors.size() > 0) {
        Sensor s = sensors.get(0);
        manager.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
      }
    }

    {
      List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_ACCELEROMETER);
      if (sensors.size() > 0) {
        Sensor s = sensors.get(0);
        manager.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
      }
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    switch (event.sensor.getType()) {
      case Sensor.TYPE_MAGNETIC_FIELD:
        mMagneticValues = event.values.clone();
        break;
      case Sensor.TYPE_ACCELEROMETER:
        mAccelerometerValues = event.values.clone();
        break;
      default:
        return;
    }

    if (mMagneticValues != null && mAccelerometerValues != null) {
      float[] rotationMatrix = new float[MATRIX_SIZE];
      float[] inclinationMatrix = new float[MATRIX_SIZE];
      float[] remapedMatrix = new float[MATRIX_SIZE];
      float[] orientationValues = new float[DIMENSION];
      SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, mAccelerometerValues, mMagneticValues);
      SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapedMatrix);
      SensorManager.getOrientation(remapedMatrix, orientationValues);

      int zAngle = (int)(orientationValues[0] * RAD2DEG) + 90;

      String str = "Z軸:" + zAngle;
      values.setText(str);

      int requestAngle = zAngle < 0 ? 0 : zAngle;
      requestAngle = 180 - requestAngle;

      if (prevTime + 200 < System.currentTimeMillis()) {
        prevTime = System.currentTimeMillis();

        try {
          if (mqttAndroidClient.isConnected())
            mqttAndroidClient.publish("pan", String.valueOf(requestAngle).getBytes(), 0, false);
        } catch (MqttException e) {
          e.printStackTrace();
        }
      }
    }
  }

  public void setImageError() {
    Log.d("SENRIGAN", "Image Error!!!!!");
  }

  public class DoRead extends AsyncTask<String, Void, MjpegInputStream> {

    private final MjpegView view;

    public DoRead(MjpegView view) {
      this.view = view;
    }

    protected MjpegInputStream doInBackground(String... url) {
      //TODO: if camera has authentication deal with it and don't just not work
      HttpResponse res = null;
      DefaultHttpClient httpclient = new DefaultHttpClient();
      HttpParams httpParams = httpclient.getParams();
      HttpConnectionParams.setConnectionTimeout(httpParams, 5 * 1000);
      HttpConnectionParams.setSoTimeout(httpParams, 5 * 1000);
      try {
        res = httpclient.execute(new HttpGet(URI.create(url[0])));
        if (res.getStatusLine().getStatusCode() == 401) {
          //You must turn off camera User Access Control before this will work
          return null;
        }
        return new MjpegInputStream(res.getEntity().getContent());
      } catch (ClientProtocolException e) {
      } catch (IOException e) {
      }

      Log.d(LOGGER_TAG, "DoRead initialize done.");
      return null;
    }

    protected void onPostExecute(MjpegInputStream result) {
      view.setSource(result);

      Log.d(LOGGER_TAG, "set source.");
      if (result != null) {
        result.setSkip(1);
        setTitle(R.string.app_name);
      } else {
        setTitle("disconnected");
      }
      view.setDisplayMode(MjpegView.SIZE_BEST_FIT);
      view.showFps(false);

      Log.i(LOGGER_TAG, "Show streaming start!");
    }
  }
}


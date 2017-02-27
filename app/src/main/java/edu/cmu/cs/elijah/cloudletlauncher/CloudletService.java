package edu.cmu.cs.elijah.cloudletlauncher;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

import javax.net.ssl.HttpsURLConnection;

import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;
import edu.cmu.cs.elijah.cloudletlauncher.api.ICloudletService;
import edu.cmu.cs.elijah.cloudletlauncher.api.ICloudletServiceCallback;

public class CloudletService extends Service {
    private static final String LOG_TAG = "CloudletService";

    // User info
    private String userId = "";

    // Cloudlet info
    private String cloudletIP = "8.225.186.10";
    private int cloudletPort = 9127;
    private String vmIp = "";

    // Message types
    private static final int MSG_POST_DONE = 0;
    private static final int MSG_GET_DONE = 1;
    private static final int MSG_VPN_CONNECTED = 2;

    // OpenVPN connection
    private IOpenVPNAPIService mVpnService = null;
    private boolean isVpnServiceReady = false;

    // Callbacks
    private final RemoteCallbackList<ICloudletServiceCallback> callbackList = new RemoteCallbackList<ICloudletServiceCallback>();

    // Modes
    private boolean isTesting = false;

    @Override
    public void onCreate() {
        Log.v(LOG_TAG, "++onCreate");
        super.onCreate();

        // Bind to the OpenVPN service
        Intent intentVpnService = new Intent(IOpenVPNAPIService.class.getName());
        Log.i(LOG_TAG, IOpenVPNAPIService.class.getName());
        intentVpnService.setPackage("de.blinkt.openvpn");

        bindService(intentVpnService, mConnection, Context.BIND_AUTO_CREATE);

        // Get user (device) ID from file
        try {
            File idFile = new File(Environment.getExternalStorageDirectory(), "/CloudletLauncher/id.txt");
            BufferedReader reader = new BufferedReader(new FileReader(idFile));
            userId = reader.readLine();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error reading ID file: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        if (mVpnService != null) {
            try {
                mVpnService.unregisterStatusCallback(mCallback);
                mVpnService.disconnect();
                isVpnServiceReady = false;
            } catch (SecurityException e) {
            } catch (RemoteException e) {}
        }

        unbindService(mConnection);

        Log.v(LOG_TAG, "++onDestroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final ICloudletService.Stub mBinder = new ICloudletService.Stub() {
        public void startOpenVpn() {
            if (checkVpnService()) {
                isTesting = true;
                connectVpn();
            }
        }

        public void endOpenVpn() {
            if (checkVpnService()) {
                isTesting = false;
                disconnectVpn();
            }
        }

        public void findCloudlet(String appId) {
            Log.d(LOG_TAG, "findCloudlet called");
            if (checkVpnService()) {
                new SendPostRequestAsync().execute("http://" + cloudletIP + ":" + cloudletPort, "create", appId, userId);
            }
        };

        public void disconnectCloudlet(String appId) {
            Log.d(LOG_TAG, "disconnectCloudlet called");
            if (checkVpnService()) {
                disconnectVpn();
                new SendPostRequestAsync().execute("http://" + cloudletIP + ":" + cloudletPort, "delete", appId, userId);
            }
        };

        public void registerCallback(ICloudletServiceCallback cb) {
            callbackList.register(cb);
            Log.d(LOG_TAG, "Callback from other apps registered");
        }

        public void unregisterCallback(ICloudletServiceCallback cb) {
            callbackList.unregister(cb);
            Log.d(LOG_TAG, "Callback from other apps unregistered");
        }
    };


    /***** Begin handling http connections ********************************************************/
    private class PostMsgWrapper
    {
        public String response;
        public String action;
        public String appId;
        public String userId;
    }

    private String sendPostRequest(String... paras) {
        try {
            URL url = new URL(paras[0]); // here is your URL path
            String action = paras[1];
            String appId = paras[2];
            String userId = paras[3];

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(15000 /* milliseconds */);
            conn.setConnectTimeout(15000 /* milliseconds */);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);

            Uri.Builder builder = new Uri.Builder()
                    .appendQueryParameter("user_id", userId)
                    .appendQueryParameter("app_id", appId)
                    .appendQueryParameter("action", action);
            String query = builder.build().getEncodedQuery();

            OutputStream os = conn.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            writer.write(query);
            writer.flush();
            writer.close();
            os.close();

            int responseCode = conn.getResponseCode();

            if (responseCode == HttpsURLConnection.HTTP_OK) {
                String serverResponse = readStream(conn.getInputStream());
                return serverResponse;
            }
        }
        catch(Exception e){
            Log.e(LOG_TAG, "Error in sending POST message: " + e.getMessage());
        }
        return null;
    }

    public class SendPostRequestAsync extends AsyncTask<String, Void, PostMsgWrapper> {
        @Override
        protected PostMsgWrapper doInBackground(String... paras) {
            PostMsgWrapper p = new PostMsgWrapper();
            p.response = sendPostRequest(paras);
            p.action = paras[1];
            p.appId = paras[2];
            p.userId = paras[3];
            return p;
        }

        @Override
        protected void onPostExecute(PostMsgWrapper p) {
            if (p.response != null) {
                Message msg = Message.obtain();
                msg.what = MSG_POST_DONE;
                msg.obj = p;
                mHandler.sendMessage(msg);
            }
        }
    }


    private String sendGetRequest(String... paras) {
        try {
            URL url = new URL(paras[0]);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(15000 /* milliseconds */);
            conn.setConnectTimeout(15000 /* milliseconds */);

            int responseCode = conn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                String serverResponse = readStream(conn.getInputStream());
                return serverResponse;
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error in sending GET message: " + e.getMessage());
        }

        return null;
    }

    public class SendGetRequestAsync extends AsyncTask<String , Void ,String> {
        @Override
        protected String doInBackground(String... paras) {
            return sendGetRequest(paras);
        }

        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                Message msg = Message.obtain();
                msg.what = MSG_GET_DONE;
                mHandler.sendMessage(msg);
            }
        }
    }

    // Converting InputStream to String
    private String readStream(InputStream in) {
        BufferedReader reader = null;
        StringBuffer response = new StringBuffer();
        try {
            reader = new BufferedReader(new InputStreamReader(in));
            String line = "";
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error in reading response from server: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {}
            }
        }
        return response.toString();
    }

    /***** Begin handling connection to OpenVPN service *******************************************/
    private void connectVpn() {
        // load client configuration file for OpenVPN
        String configStr = "";
        try {
            //InputStream conf = getApplicationContext().getAssets().open("test.conf");
            //BufferedReader reader = new BufferedReader(new InputStreamReader(conf));
            File confFile = new File(Environment.getExternalStorageDirectory(), "/CloudletLauncher/OpenVPN_client.conf");
            BufferedReader reader = new BufferedReader(new FileReader(confFile));

            String line;
            while(true) {
                line = reader.readLine();
                if(line == null)
                    break;
                configStr += line + "\n";
            }
            reader.readLine();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error reading .conf file: " + e.getMessage());
        }

        // Start connection
        if (mVpnService != null) {
            try {
                mVpnService.startVPN(configStr);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error in starting VPN connection: " + e.getMessage());
            }
        }
    }

    private void disconnectVpn() {
        if (mVpnService != null) {
            try {
                mVpnService.disconnect();
            } catch (SecurityException e) {
                Log.w(LOG_TAG, "The cloudletlauncher hasn't registered to OpenVPN client");
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error in disconnecting VPN service: " + e.getMessage());
            }
        }
    }

    private IOpenVPNStatusCallback mCallback = new IOpenVPNStatusCallback.Stub() {
        @Override
        public void newStatus(String uuid, String state, String message, String level)
                throws RemoteException {
            Log.d(LOG_TAG, state + "|" + message);
            messageAllApps(state + "|" + message);
            if (state.equals("CONNECTED")) {
                Message msg = Message.obtain();
                msg.what = MSG_VPN_CONNECTED;
                mHandler.sendMessage(msg);
            }
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            // This is called when the connection with the service has been established
            mVpnService = IOpenVPNAPIService.Stub.asInterface(service);

            try {
                mVpnService.registerStatusCallback(mCallback);
                isVpnServiceReady = true;
                Log.i(LOG_TAG, "Connected to OpenVPN service and callback registered");
            } catch (SecurityException e) {
                Log.w(LOG_TAG, "The cloudletlauncher needs to register to OpenVPN client first!");
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error in registering callback to OpenVPN service: " + e.getMessage());
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            mVpnService = null;
        }
    };
    /***** End handling connection to OpenVPN service *********************************************/

    /***** Begin helper functions *****************************************************************/
    private void messageAllApps(String message) {
        int n = callbackList.beginBroadcast();
        for(int i = 0; i < n; i++) {
            try {
                callbackList.getBroadcastItem(i).message(message);
            } catch (RemoteException e) {
                // RemoteCallbackList will take care of removing dead objects
            }
        }
        callbackList.finishBroadcast();
    }

    private boolean checkVpnService() {
        if (!isVpnServiceReady) {
            try {
                mVpnService.registerStatusCallback(mCallback);
                isVpnServiceReady = true;
                Log.i(LOG_TAG, "OpenVPN callback registered");
            } catch (SecurityException e) {
                Log.w(LOG_TAG, "The cloudletlauncher needs to register to OpenVPN client first!");
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Error in registering callback to OpenVPN service: " + e.getMessage());
            }
        }
        return isVpnServiceReady;
    }
    /***** End helper functions *******************************************************************/

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MSG_POST_DONE) {
                PostMsgWrapper p = (PostMsgWrapper) msg.obj;
                if (p.action.equals("create")) {
                    final Timer pollingTimer = new Timer();
                    StatusCheckTask pollingTask = new StatusCheckTask(p.userId, p.appId, pollingTimer);
                    pollingTimer.schedule(pollingTask, 10000, 3000);
                }
            }
            if (msg.what == MSG_VPN_CONNECTED) {
                if (isTesting) {
                    Log.d(LOG_TAG, "Not broadcasting new IP because running in testing mode.");
                    // do nothing
                } else {
                    int n = callbackList.beginBroadcast();
                    for (int i = 0; i < n; i++) {
                        try {
                            callbackList.getBroadcastItem(i).newServerIP(vmIp);
                        } catch (RemoteException e) {
                            // RemoteCallbackList will take care of removing dead objects
                        }
                    }
                    callbackList.finishBroadcast();
                }
            }
        }
    };

    class StatusCheckTask extends TimerTask  {
        String userId;
        String appId;
        Timer timer;

        public StatusCheckTask(String userId, String appId, Timer timer) {
            this.userId = userId;
            this.appId = appId;
            this.timer = timer;
        }

        @Override
        public void run() {
            String response = sendGetRequest("http://" + cloudletIP + ":" + cloudletPort + "?user_id=" + this.userId + "&app_id=" + this.appId);
            Log.d(LOG_TAG, response);
            if (response != null && !response.equals("None")) {
                this.timer.cancel();
                vmIp = response;
                connectVpn();
            }
        }
    }
}

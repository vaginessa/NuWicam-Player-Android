package com.nuvoton.nuwicam;


import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.view.View.OnClickListener;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.os.Handler;

import com.appunite.ffmpeg.FFmpegError;
import com.appunite.ffmpeg.FFmpegPlayer;
import com.appunite.ffmpeg.FFmpegListener;
import com.appunite.ffmpeg.FFmpegDisplay;
import com.appunite.ffmpeg.FFmpegStreamInfo;
import com.appunite.ffmpeg.NotPlayingException;
import com.longevitysoft.android.xml.plist.domain.PListObject;
import com.longevitysoft.android.xml.plist.domain.sString;
import com.nuvoton.socketmanager.ReadConfigure;
import com.nuvoton.socketmanager.SocketInterface;
import com.nuvoton.socketmanager.SocketManager;

import net.wimpi.modbus.Modbus;
import net.wimpi.modbus.ModbusException;
import net.wimpi.modbus.io.ModbusRTUTCPTransaction;
import net.wimpi.modbus.msg.ReadMultipleRegistersRequest;
import net.wimpi.modbus.msg.ReadMultipleRegistersResponse;
import net.wimpi.modbus.msg.WriteSingleRegisterRequest;
import net.wimpi.modbus.msg.WriteSingleRegisterResponse;
import net.wimpi.modbus.net.RTUTCPMasterConnection;
import net.wimpi.modbus.procimg.Register;
import net.wimpi.modbus.procimg.SimpleRegister;

import org.json.JSONObject;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A simple {@link Fragment} subclass.
 */
public class LiveFragment extends Fragment implements OnClickListener, OnSeekBarChangeListener, FFmpegListener, SocketInterface{
    private boolean isModbusInPolling = true;
    final Lock lock = new ReentrantLock();
    final Condition waitForWrite = lock.newCondition(), waitForRead = lock.newCondition();
    private WriteSingleRegisterRequest wreq;
    private WriteSingleRegisterResponse wres;
    private ArrayList<ImageButton> lightButtonList = new ArrayList<>();
    private int temperature, light;
    private int [] lightArray = new int[6];
    private ReadMultipleRegistersResponse res;
    private ReadMultipleRegistersRequest req;
    private RTUTCPMasterConnection con1 = null;
    private ModbusRTUTCPTransaction trans1 = null;
    private InetAddress addr = null;
    int port = Modbus.DEFAULT_PORT;
    private String modbusURL;
    private boolean isTCP = false;
    private Handler handler = new Handler();
    private static int counter = 0;
    private Timer modbusTimer, checkTimer, pollingTimer;
    private boolean flashOn = true;
    private String localURL;
    private SocketManager socketManager;
    private ReadConfigure configure;
    private int orientation;
    private String plarform, cameraSerial;
    private ProgressBar progressBar;
    private TextView onlineText, temperatureText;
    private ImageView redDot;
    private boolean isPlaying = false, isTracking = false;
    private int mCurrentTimeS;
    private View thisView;
    private FFmpegPlayer mMpegPlayer;
    private SurfaceView mVideoView;
    private SeekBar seekBar;
    private ImageButton snapshotButton, playButton, expandButton;
    private int mAudioStreamNo = FFmpegPlayer.UNKNOWN_STREAM;
    private int mSubtitleStreamNo = FFmpegPlayer.NO_STREAM;
    private static final String TAG = "LiveFragment";

    private boolean isHide = false;
    OnHideBottomBarListener mCallback;

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.snapshotButton:
                Log.d(TAG, "onClick: snapshot");
                break;
            case R.id.playButton:
                Log.d(TAG, "onClick: play");
                playButton.setEnabled(false);
                if (isPlaying == false){
                    isPlaying = true;
                    repeatCheck(true);
                }else {
                    isPlaying = false;
                    mMpegPlayer.stop();
                }
                break;
            case R.id.expandButton:
                Log.d(TAG, "onClick: expand");
                break;
            default:
                break;
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        Log.d(TAG, "onProgressChanged:");
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        isTracking = true;
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        isTracking = false;
    }

    public interface OnHideBottomBarListener{
        public void onHideBottomBar(boolean isHide);
    }


    public LiveFragment() {
        // Required empty public constructor
    }

    public static LiveFragment newInstance(Bundle b){
        LiveFragment fragment = new LiveFragment();
        fragment.setArguments(b);
        return fragment;
    }

    public void registerUI(){
        playButton = (ImageButton) thisView.findViewById(R.id.playButton);
        playButton.setOnClickListener(this);
        playButton.setEnabled(false);

        expandButton = (ImageButton) thisView.findViewById(R.id.expandButton);
        expandButton.setOnClickListener(this);
        expandButton.setEnabled(false);

        seekBar = (SeekBar) thisView.findViewById(R.id.seekBar);
        seekBar.setOnSeekBarChangeListener(this);
        seekBar.setEnabled(false);

        onlineText = (TextView) thisView.findViewById(R.id.onlineText);
        temperatureText = (TextView) thisView.findViewById(R.id.temperature);
        progressBar = (ProgressBar) thisView.findViewById(R.id.progressBar);
        redDot = (ImageView) thisView.findViewById(R.id.redDot);
        for (int i=0; i<6; i++){
            final int lightIndex = i;
            final ImageButton button = (ImageButton) thisView.findViewWithTag(String.valueOf(100 + i));
            button.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    repeatModbus(false);
                    lock.lock();
                    while (isModbusInPolling){
                        try {
                            waitForRead.await();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    int lightValue = lightArray[lightIndex], lightFinalValue = 0;
                    lightValue = (lightValue == 0) ? 1 : 0;
                    lightArray[lightIndex] = lightValue;
                    for (int i=0; i<6; i++){
                        lightFinalValue =  (lightArray[i] << i) | lightFinalValue ;
                    }
                    light = lightFinalValue;
                    if (lightIndex == 0 || lightIndex == 1){
                        if (lightValue == 0){
                            button.setImageResource(R.drawable.recordflashon);
                        }else {
                            button.setImageResource(R.drawable.recordflashoff);
                        }
                    }else if (lightIndex == 2 || lightIndex == 3){
                        if (lightValue == 0){
                            button.setImageResource(R.drawable.lighton);
                        }else {
                            button.setImageResource(R.drawable.lightoff);
                        }
                    }else{
                        if (lightValue == 0){
                            button.setImageResource(R.drawable.lightblueon);
                        }else {
                            button.setImageResource(R.drawable.lightblueoff);
                        }
                    }
                    new SetLightValueTask().execute("");
                }
            });
            lightButtonList.add(button);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        thisView = inflater.inflate(R.layout.fragment_live, container, false);
        registerUI();
        determineOrientation();
        if (socketManager == null){
            socketManager = new SocketManager();
        }
        socketManager.setSocketInterface(this);
        // Inflate the layout for this fragment
        return thisView;
    }

    @Override
    public void onPause() {
        super.onPause();
        repeatCheck(false);
        repeatRedDot(false);
        repeatPolling(false);
        repeatModbus(false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState){
        super.onActivityCreated(savedInstanceState);
        mVideoView = (SurfaceView) getActivity().findViewById(R.id.videoView);
        mVideoView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Log.d(TAG, "onTouch: ");
                if (!isHide){
                    isHide = true;
                }else {
                    isHide = false;
                }
                mCallback.onHideBottomBar(isHide);
                return false;
            }
        });
        mMpegPlayer = new FFmpegPlayer((FFmpegDisplay) mVideoView, this);
        configure = ReadConfigure.getInstance(getActivity().getApplicationContext());

    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d(TAG, "onConfigurationChanged: live fragment");
        super.onConfigurationChanged(newConfig);
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        populateViewForOrientation(inflater, (ViewGroup) getView());
        registerUI();
        determineOrientation();
        mVideoView = (SurfaceView) getView().findViewById(R.id.videoView);
        mVideoView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isHide){
                    isHide = true;
                }else {
                    isHide = false;
                }
                mCallback.onHideBottomBar(isHide);
                return false;
            }
        });
        mMpegPlayer = new FFmpegPlayer((FFmpegDisplay) mVideoView, this);
        repeatCheck(false);
        repeatCheck(true);
    }

    private void populateViewForOrientation(LayoutInflater inflater, ViewGroup viewGroup) {
        viewGroup.removeAllViewsInLayout();
        View subview = inflater.inflate(R.layout.fragment_live, viewGroup);

        // Find your buttons in subview, set up onclicks, set up callbacks to your parent fragment or activity here.
        // You can create ViewHolder or separate method for that.
        // example of accessing views: TextView textViewExample = (TextView) view.findViewById(R.id.text_view_example);
        // textViewExample.setText("example");
    }

    @Override
    public void onStart() {
        super.onStart();
        try{
            mCallback = (OnHideBottomBarListener) getActivity();
        }catch (ClassCastException e){
            throw new ClassCastException(getActivity().toString() + " must implement onHideBottomBarListener");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Bundle bundle = getArguments();
        if (bundle != null){
            plarform = getArguments().getString("Platform");
            cameraSerial = getArguments().getString("CameraSerial");
        }
        repeatCheck(true);
    }

    public void determineOrientation(){
        orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE){
            isHide = true;
        }else {
            isHide = false;
        }
    }
    private class TimerSetDataSource extends TimerTask{
        public void run(){
            Log.d(TAG, "run: timer set data source");
            sendCheckStorage();
        }
    }

    Runnable timerSetRedDot = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "run: timer set red dot handler" + String.valueOf(flashOn));
            if (flashOn == true){
                redDot.setImageResource(R.drawable.recordflashoff);
                flashOn = false;
            }else {
                redDot.setImageResource(R.drawable.recordflashon);
                flashOn = true;
            }
            handler.postDelayed(this, 1000);
        }
    };

    private class TimerSetRedDot extends TimerTask{
        public void run(){
            Log.d(TAG, "run: timer set red dot" + String.valueOf(flashOn));
            if (flashOn == true){
                redDot.setImageResource(R.drawable.recordflashoff);
                flashOn = false;
            }else {
                redDot.setImageResource(R.drawable.recordflashon);
                flashOn = true;
            }
        }
    }

    private class TimerPollingCheck extends TimerTask{
        public void run(){
            Log.d(TAG, "run: timer polling check " + String.valueOf(counter));
            if (counter >= 5){
//                onlineText.setText(R.string.offline);
                repeatCheck(true);
                repeatRedDot(false);
                repeatPolling(false);
                repeatModbus(false);

            }
            counter++;
        }
    }

    private void repeatCheck(boolean option){
        Log.d(TAG, "repeatCheck: " + String.valueOf(option));
        if (option == true){
            checkTimer = new Timer(true);
            checkTimer.schedule(new TimerSetDataSource(), 0, 5000);
        }else {
            if (checkTimer != null){
                checkTimer.cancel();
            }

        }
    }

    private void repeatRedDot(boolean option){
        Log.d(TAG, "repeatRedDot: " + String.valueOf(option));
        if (option == true){
            handler.post(timerSetRedDot);
//            redDotTimer = new Timer(true);
//            redDotTimer.schedule(new TimerSetRedDot(), 0, 1000);
        }else if (option == false){
//            redDotTimer.cancel();
            handler.removeCallbacks(timerSetRedDot);
        }
    }

    private void repeatPolling(boolean option){
        Log.d(TAG, "repeatPolling: " + String.valueOf(option));
        if (option == true){
            pollingTimer = new Timer(true);
            pollingTimer.schedule(new TimerPollingCheck(), 0, 10000);
        }else {
            if (pollingTimer != null){
                pollingTimer.cancel();
            }
        }
    }


    private void setDataSource() {
        progressBar.post(new Runnable() {
            @Override
            public void run() {
                progressBar.setVisibility(View.VISIBLE);
            }
        });

        HashMap<String, String> params = new HashMap<String, String>();
        // set font for ass
        File assFont = new File(Environment.getExternalStorageDirectory(),
                "DroidSansFallback.ttf");
        params.put("ass_default_font_path", assFont.getAbsolutePath());
        params.put("fflags", "nobuffer");
        params.put("probesize", "5120");
        params.put("flush_packets", "1");
        if (isTCP){
            params.put("rtsp_transport", "tcp");
        }
        mMpegPlayer.setMpegListener(this);
        mMpegPlayer.setDataSource(localURL, params, FFmpegPlayer.UNKNOWN_STREAM, mAudioStreamNo,
                mSubtitleStreamNo);
    }

    // FFMPEG interface implementation

    public void onFFDataSourceLoaded(FFmpegError err, FFmpegStreamInfo[] streams){
        if (err != null){
            String format = "Could not open stream";
            Log.d(TAG, "onFFDataSourceLoaded: " + format);
            progressBar.setVisibility(View.VISIBLE);
            onlineText.setText(R.string.offline);
            onlineText.setTextColor(0xFFFFFF);
        }
        Log.d(TAG, "onFFDataSourceLoaded: loaded");
        progressBar.setVisibility(View.GONE);
//        mMpegPlayer.pause();
        mMpegPlayer.resume();
        repeatCheck(false);
    }

    public void onFFResume(NotPlayingException result){
        isPlaying = true;
        Log.d(TAG, "onFFResume: ");
        playButton.setImageResource(R.drawable.pause);
        playButton.setEnabled(true);
        repeatRedDot(true);
    }

    public void onFFPause(NotPlayingException err){
        isPlaying = false;
        Log.d(TAG, "onFFPause: ");
        playButton.setImageResource(R.drawable.play);
        playButton.setEnabled(true);
        repeatRedDot(false);
    }

    public void onFFStop(){
        isPlaying = false;
        Log.d(TAG, "onFFStop: ");
        playButton.setImageResource(R.drawable.play);
        playButton.setEnabled(true);
        repeatRedDot(false);
    }

    public void onFFUpdateTime(long currentTimeUs, long videoDurationUs, boolean isFinished){
        Log.d(TAG, "onFFUpdateTime: ");
        counter = 0;
        if ( isTracking == false){
            mCurrentTimeS = (int)(currentTimeUs / 1000000);
            int videoDurationS = (int)(videoDurationUs / 1000000);
            seekBar.setMax(videoDurationS);
            seekBar.setProgress(mCurrentTimeS);
        }
        if (isFinished == true){
            playButton.setImageResource(R.drawable.play);
            isPlaying = false;
        }
    }

    public void onFFSeeked(NotPlayingException result){
        Log.d(TAG, "onFFSeeked: ");
    }

    //socket manager delegate
    @Override
    public void showToastMessage(String message) {

    }

    @Override
    public void updateFileList(ArrayList<FileContent> fileList) {

    }

    @Override
    public void deviceIsAlive() {
        onlineText.setText(R.string.online);
//        onlineText.setTextColor(0x000000);
        repeatCheck(false);
        repeatPolling(true);
        setDataSource();
        repeatModbus(true);
    }

    @Override
    public void updateSettingContent(String category, String value) {

    }

    @Override
    public void updateSettingContent(String category, JSONObject jsonObject) {

    }

    private String getDeviceURL(){
        String cameraName = "Setup Camera " + cameraSerial;
        SharedPreferences preference = getActivity().getSharedPreferences(cameraName, Context.MODE_PRIVATE);
        String urlString = preference.getString("URL", "DEFAULT");
        isTCP = preference.getBoolean("Transmission", false);
        localURL = new String(urlString);
        String [] ipCut = urlString.split("/");
        String ip = modbusURL = ipCut[2];
        String url = "http://" + ip + ":80/cgi-bin/";
        return url;
    }

    private void sendCheckStorage(){
        String command = getDeviceURL();
        sString baseCommand, action;
        ArrayList<Map> fileCommandSet = configure.videoCommandSet;
        Map<String, PListObject> targetCommand = fileCommandSet.get(0);
        baseCommand = (sString) targetCommand.get("Base Command");
        command = command + baseCommand.getValue();
        if (socketManager != null){
            socketManager.executeSendGetTask(command, SocketManager.CMDGET_ALIVE);
        }
    }

    private class TimerPollingModbus extends TimerTask{
        @Override
        public void run() {
            isModbusInPolling = true;
            int ref = 3;
            int count = 5;
            try {
                addr = InetAddress.getByName("192.168.100.1");
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
            if (con1 == null){
                con1 = new RTUTCPMasterConnection(addr, Modbus.DEFAULT_PORT);
                try {
                    con1.connect();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (con1.isConnected()){
                lock.lock();
                while (!isModbusInPolling){
                    try {
                        waitForWrite.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(TAG, "doInBackground: modbus connected");
                req = new ReadMultipleRegistersRequest(ref, count);
                req.setUnitID(1);
                trans1 = new ModbusRTUTCPTransaction(con1);
                trans1.setRequest(req);
                try {
                    trans1.execute();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    res = (ReadMultipleRegistersResponse) trans1.getResponse();
                    if (res != null){
                        final Register [] registers = res.getRegisters();
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setModbusUI(registers);
                            }
                        });
                        isModbusInPolling = false;
                        waitForRead.signal();
                        lock.unlock();
                        Log.d(TAG, "modbus doInBackground: " + String.valueOf(registers[1].getValue()) + "temp: " + String.valueOf(registers[4].getValue()));
                    }else {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                temperatureText.setText("N/A \u2103");
                            }
                        });
                        Log.d(TAG, "modbus doInBackground: null res");
                    }

                } catch (ModbusException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void repeatModbus(boolean option){
        if (option){
            modbusTimer = new Timer();
            modbusTimer.schedule(new TimerPollingModbus(), 0, 1100);
        }else {
            modbusTimer.cancel();
            con1.close();
        }

    }

    private void setModbusUI(Register [] registers){
        light = registers[1].getValue();
        for (int i=0; i<6; i++){
            ImageButton button = lightButtonList.get(i);
            int lightValue = (light >> i) & 1;
            lightArray [i] = lightValue;
            if (i == 0 || i== 1){
                if (lightValue == 0){
                    button.setImageResource(R.drawable.recordflashon);
                }else {
                    button.setImageResource(R.drawable.recordflashoff);
                }
            }else if (i == 2 || i == 3){
                if (lightValue == 0){
                    button.setImageResource(R.drawable.lighton);
                }else {
                    button.setImageResource(R.drawable.lightoff);
                }
            }else{
                if (lightValue == 0){
                    button.setImageResource(R.drawable.lightblueon);
                }else {
                    button.setImageResource(R.drawable.lightblueoff);
                }

            }
        }
        temperature = registers[4].getValue();
        temperatureText.setText(String.valueOf(temperature) + " \u2103");
    }
    private class SetLightValueTask extends AsyncTask<String, Void, Void>{

        @Override
        protected Void doInBackground(String... params) {
                int ref = 4;
                try {
                    addr = InetAddress.getByName("192.168.100.1");
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }

                if (con1 == null || !con1.isConnected()) {
                    con1 = new RTUTCPMasterConnection(addr, Modbus.DEFAULT_PORT);
                    try {
                        con1.connect();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (con1.isConnected()) {
                    Register register = new SimpleRegister(light);
                    wreq = new WriteSingleRegisterRequest(ref, register);
                    wreq.setUnitID(1);
                    Log.d(TAG, "doInBackground: modbus connected");
                    trans1 = new ModbusRTUTCPTransaction(con1);
                    trans1.setRequest(wreq);
                    try {
                        trans1.execute();
                        Thread.sleep(100);
                        wres = new WriteSingleRegisterResponse();
                        Log.d(TAG, "doInBackground: " + wres);
                        if (wres != null){
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    isModbusInPolling = true;
                                    waitForWrite.signal();
                                    lock.unlock();
                                }
                            });
                            repeatModbus(true);
                        }
                    } catch (ModbusException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            return null;
        }
    }

}


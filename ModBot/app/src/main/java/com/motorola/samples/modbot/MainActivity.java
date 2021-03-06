package com.motorola.samples.modbot;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.os.Bundle;
import android.graphics.Color;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.motorola.mod.ModDevice;
import com.motorola.mod.ModManager;


public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    ModManagerInterface modMgr;
    ModBotRaw modBotRaw;

    boolean modActive = false;
    byte curLeft  = Constants.STOP;
    byte curRight = Constants.STOP;

    // Show state
    View lfv;   // Left forward view
    View rfv;   // Right forward view
    View lrv;   // Left reverse view
    View rrv;   // Right reverse view
    SeekBar speedControl;
    TextView vidView;  // Current Mod's VID/PID
    private float right;
    private float left;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        lfv = findViewById(R.id.left_forward);
        rfv = findViewById(R.id.right_forward);
        lrv = findViewById(R.id.left_reverse);
        rrv = findViewById(R.id.right_reverse);
        vidView = (TextView) findViewById(R.id.vidView);
        speedControl = (SeekBar) findViewById(R.id.speed_control);
        speedControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                processUI();
                processSpeeds(false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        lfv.setOnClickListener(this);
        rfv.setOnClickListener(this);
        lrv.setOnClickListener(this);
        rrv.setOnClickListener(this);
        vidView.setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        setAllBoxes(Color.RED);
        initModManager();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (modBotRaw != null) {
            curLeft  = Constants.STOP;
            curRight = Constants.STOP;
            modBotRaw.setSpeed(curLeft, curRight);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseModManager();
    }

    // Initialize connection
    private void initModManager() {
        if (null == modMgr) {
            modMgr = new ModManagerInterface(this);
            modMgr.registerListener(modHandler);
        }
    }

    private void releaseModManager() {
        if (null != modMgr) {
            modMgr.onDestroy();
            modMgr = null;
        }
    }

    // Handle messages from the ModManagerInterface
    private Handler modHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ModManagerInterface.MSG_MOD_DEVICE:
                    ModDevice device = modMgr.getModDevice();
                    onModDevice(device);
                    break;
            }
        }
    };

    // Handle Mod attach and detach
    private void onModDevice(ModDevice device) {
        if (null == device) {
            if (null != modBotRaw) {
                modBotRaw.onModDevice(null);
                modBotRaw = null;
            }
            setAllBoxes(Color.RED);
            vidView.setText(getResources().getString(R.string.no_mod));
            modActive = false;
        } else {
            if (device.getVendorId() == Constants.VID_DEVELOPER) {
                if (null == modBotRaw) {
                    modBotRaw = new ModBotRaw(this, modMgr);
                    modBotRaw.registerListener(rawHandler);
                }
                modBotRaw.onModDevice(device);
            }
            String vidText =
                    String.format("0x%08x / 0x%08x", device.getVendorId(), device.getProductId());
            vidView.setText(vidText);
            // Don't set modActive until the Raw interface is successfully opened
        }
    }

    private static int RAW_REQUEST_CODE = 0x25;
    private Handler rawHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ModBotRaw.MSG_RAW_REQUEST_PERMISSION:
                    // ModRaw will check if it has permission for Raw.  If not, Activity
                    // must handle the request and notify ModBotRaw when granted
                    requestPermissions(new String[]{ModManager.PERMISSION_USE_RAW_PROTOCOL},
                            RAW_REQUEST_CODE);
                    break;
                case ModBotRaw.MSG_RAW_IO_READY:
                    //Enable the UX Controls here
                    setAllBoxes(Color.BLACK);
                    modActive = true;
                    break;
                case ModBotRaw.MSG_RAW_DATA:
                    //No data expected from Mod
                    break;
            }
        }
    };

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == RAW_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                modBotRaw.onPermissionGranted(true);
            } else {
                // To pop up a description dialog or other prompts to explain app cannot work
                // without permission.
            }
        }
    }

    private void setAllBoxes(int color) {
        lfv.setBackgroundColor(color);
        rfv.setBackgroundColor(color);
        lrv.setBackgroundColor(color);
        rrv.setBackgroundColor(color);
    }

    // Handle input from a Game Controller
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // Check that the event came from a game controller
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) ==
                InputDevice.SOURCE_JOYSTICK &&
                event.getAction() == MotionEvent.ACTION_MOVE) {

            // Process all historical movement samples in the batch
            final int historySize = event.getHistorySize();

            processJoystickInput(event, -1);

            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private static float getCenteredAxis(MotionEvent event,
                                         InputDevice device, int axis, int historyPos) {
        final InputDevice.MotionRange range =
                device.getMotionRange(axis, event.getSource());

        // A joystick at rest does not always report an absolute position of
        // (0,0). Use the getFlat() method to determine the range of values
        // bounding the joystick axis center.
        if (range != null) {
            final float flat = range.getFlat();
            final float value =
                    historyPos < 0 ? event.getAxisValue(axis):
                            event.getHistoricalAxisValue(axis, historyPos);

            // Ignore axis values that are within the 'flat' region of the
            // joystick axis center.
            if (Math.abs(value) > flat) {
                return value;
            }
        }
        return 0;
    }

    private void processJoystickInput(MotionEvent event,
                                      int historyPos) {

        InputDevice mInputDevice = event.getDevice();

        // Calculate the vertical distance to move by
        // using the input value from one of these physical controls:
        // the left control stick, hat switch, or the right control stick.
        left = getCenteredAxis(event, mInputDevice,
                MotionEvent.AXIS_Y, historyPos);
        right = getCenteredAxis(event, mInputDevice,
                MotionEvent.AXIS_RZ, historyPos);
        processUI();
        processSpeeds(true);
    }

    private void processUI(){
        if (left > 0.5f) {
            lrv.setBackgroundColor(Color.GREEN);
            lfv.setBackgroundColor(Color.BLACK);
        } else if (left < -0.5f) {
            lrv.setBackgroundColor(Color.BLACK);
            lfv.setBackgroundColor(Color.GREEN);
        } else {
            lfv.setBackgroundColor(Color.BLACK);
            lrv.setBackgroundColor(Color.BLACK);
        }
        if (right > 0.5f) {
            rrv.setBackgroundColor(Color.GREEN);
            rfv.setBackgroundColor(Color.BLACK);
        } else if (right < -0.5f) {
            rrv.setBackgroundColor(Color.BLACK);
            rfv.setBackgroundColor(Color.GREEN);
        } else {
            rfv.setBackgroundColor(Color.BLACK);
            rrv.setBackgroundColor(Color.BLACK);
        }
    }

    private void processSpeeds(boolean joystick) {
        byte newLeft = Constants.STOP;
        byte newRight = Constants.STOP;

        int speed = speedControl.getProgress()+5;
        if(joystick){
            newLeft = (byte)(-left*100.0f);
            newRight = (byte)(-right*100.0f);
        }
        else{
            if (left > 0.5f) {
                newLeft = (byte) -speed;
            } else if (left < -0.5f) {
                newLeft = (byte) speed;
            }
            if (right > 0.5f) {
                newRight = (byte) -speed;
            } else if (right < -0.5f) {
                newRight = (byte) speed;
            }
        }

        if (modActive && modBotRaw != null) {
            if ((newLeft != curLeft) || (newRight != curRight)) {
                curLeft = newLeft;
                curRight = newRight;
                modBotRaw.setSpeed(curLeft, curRight);
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.left_forward:
                left = -1;
                break;
            case R.id.right_forward:
                right = -1;
                break;
            case R.id.left_reverse:
                left = 1;
                break;
            case R.id.right_reverse:
                right = 1;
                break;
            case R.id.vidView:
                left = 0;
                right = 0;
                break;
        }
        processUI();
        processSpeeds(false);
    }
}

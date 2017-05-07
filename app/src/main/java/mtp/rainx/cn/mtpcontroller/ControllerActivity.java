package mtp.rainx.cn.mtpcontroller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDevice;
import android.mtp.MtpDeviceInfo;
import android.mtp.MtpEvent;
import android.mtp.MtpObjectInfo;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import cn.rainx.ptp.usbcamera.BaselineInitiator;
import cn.rainx.ptp.usbcamera.DeviceInfo;
import cn.rainx.ptp.usbcamera.PTPException;
import cn.rainx.ptp.usbcamera.eos.EosInitiator;
import cn.rainx.ptp.usbcamera.nikon.NikonInitiator;

public class ControllerActivity extends AppCompatActivity implements View.OnClickListener{

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    // log 显示区域
    private EditText etLogPanel;

    // 是否连接
    boolean isOpenConnected = false;

    // mtp 设备句柄
    MtpDevice mtpDevice;

    // USB 设备句柄
    UsbDevice usbDevice;

    // 对象名称
    EditText etPtpObjectName;

    CancellationSignal signal;

    // 事件端点
    protected UsbEndpoint epEv;
    // in 端点
    protected UsbEndpoint epIN;
    // out 端点
    protected UsbEndpoint epOut;


    private BaselineInitiator bi;


    // 接口
    protected UsbInterface 				intf;

    protected UsbDeviceConnection mConnection = null;


    protected boolean isCanon = false;
    protected boolean isNikon = false;


    /**
     * usb插拔接收器
     */
    BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("usb设备已接入");
                isOpenConnected = false;
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("usb设备已拔出");
                isOpenConnected = false;

                if (signal != null && !signal.isCanceled()) {
                    signal.cancel();
                    signal = null;
                }

                detachDevice();
            }
        }
    };


    // 处理权限
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        //user choose YES for your previously popup window asking for grant perssion for this usb device
                        if(null != usbDevice){
                            performConnect(usbDevice);
                        }
                    }
                    else {
                        //user choose NO for your previously popup window asking for grant perssion for this usb device
                        log("Permission denied for device" + usbDevice);
                    }
                }
            }
        }
    };


    /**
     * 注册usb设备插拔广播接收器
     */
    void registerUsbDeviceReceiver(){
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiver, filter);
    }


    final private static String TAG = "ControllerActivity";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller);
        setTitle("PTP协议测试");

        // 注册按钮点击事件
        ((Button) findViewById(R.id.allUsbDevices)).setOnClickListener(this);
        ((Button) findViewById(R.id.connectToDevices)).setOnClickListener(this);
        ((Button) findViewById(R.id.getDevicePTPInfo)).setOnClickListener(this);
        ((Button) findViewById(R.id.getAllObjects)).setOnClickListener(this);
        ((Button) findViewById(R.id.transferObject)).setOnClickListener(this);

        etPtpObjectName = (EditText) findViewById(R.id.ptpObject);

        etLogPanel = (EditText) findViewById(R.id.logPanel);
        etLogPanel.setGravity(Gravity.BOTTOM);

        log("程序初始化完成");

        registerUsbDeviceReceiver();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbReceiver);
        unregisterReceiver(usbPermissionReceiver);

        detachDevice();
    }


    void detachDevice() {
        if( mtpDevice!=null )mtpDevice.close();
        if (bi != null) {
            try {
                bi.close();
            } catch (PTPException e) {
                e.printStackTrace();
            }
        }
    }



    /**
     * 轮询获取相机事件
     */

    void pollReadEvent() {
        log("启动轮询事件线程");
        final FutureTask<Boolean> future = new FutureTask<Boolean>(
                new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws IOException {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                while (true) {
                                    MtpEvent event = mtpDevice.readEvent(signal);
                                    log("获取新的event: " + event.getEventCode());
                                }
                            } else {

                            }

                            return false;
                        } catch (OperationCanceledException exception) {
                            return true;
                        }
                    }
                });
        final Thread thread = new Thread(future);
        thread.start();
    }


    /**
     * 注册操作usb设备需要的权限
     * @param usbDevice
     */
    void registerUsbPermission( UsbDevice usbDevice ){
        log("请求USB设备访问权限");
        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbPermissionReceiver, filter);
        PendingIntent mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(usbDevice, mPermissionIntent);
    }

    // 处理按钮点击
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.allUsbDevices:
                getAllUsbDevices();
                break;
            case R.id.connectToDevices:
                connectMTPDevice();
                break;
            case R.id.getDevicePTPInfo:
                //getDevicePTPInfo();
                getDevicePTPInfoVersion2();
                break;
            case R.id.getAllObjects:
                getAllObjects();
                break;
            case R.id.transferObject:
                transferObject();
                break;
        }
    }


    public void getAllUsbDevices() {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
        int i = 0;
        while(deviceIterator.hasNext()){
            i++;
            UsbDevice device = deviceIterator.next();
            log("--------");
            log("设备 ： " + i);
            log("device id : " + device.getDeviceId());
            log("name : " + device.getDeviceName());
            log("class : " + device.getDeviceClass());
            log("subclass : " + device.getDeviceSubclass());
            log("vendorId : " + device.getVendorId());
            // log("version : " + device.getVersion() );
            log("serial number : " + device.getSerialNumber() );
            log("interface count : " + device.getInterfaceCount());
            log("device protocol : " + device.getDeviceProtocol());
            log("--------");

        }
    }

    public void getDevicePTPInfo() {
        if (isOpenConnected && mtpDevice != null) {
            log("准备获取ptp信息");
            MtpDeviceInfo mdi = mtpDevice.getDeviceInfo();
            log("getManufacturer:" + mdi.getManufacturer());
            log("getVersion:" + mdi.getVersion() );
            log("getModel:"+mdi.getModel());
            log("getSerialNumber:" + mdi.getSerialNumber());

        } else {
            log("mtp/ptp 设备未连接");
        }
    }

    public void getDevicePTPInfoVersion2() {
        if (isOpenConnected) {
            log("准备获取ptp信息v2");
            try {
                DeviceInfo deviceInfo = bi.getDeviceInfo();
                log("device info:" + deviceInfo.toString());
            } catch (PTPException e) {e.printStackTrace();}
        } else {
            log("mtp/ptp 设备未连接v2");
        }
    }

    public void getAllObjects() {
        if (isOpenConnected && mtpDevice != null) {
            log("准备获取objects信息");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int[] sids = mtpDevice.getStorageIds();
                    for (int sid : sids) {
                        log("--------");
                        log("检查storage id: " + sid);
                        log("--------");
                        int[] objectHandles = mtpDevice.getObjectHandles(sid, 0, 0);
                        log("获取sid (" + sid + ")中的对象句柄" );
                        for (int objectHandle : objectHandles) {
                            MtpObjectInfo info = mtpDevice.getObjectInfo(objectHandle);
                            if (info != null) {
                                log("handle :" + objectHandle + "name:" +  info.getName() + " , format: "
                                        + info.getFormat() + " , size:" + info.getCompressedSize()
                                        + ", created: " + info.getDateCreated()
                                    );
                            }
                        }
                    }
                }
            }).start();


        } else {
            log("mtp/ptp 设备未连接");
        }
    }

    public void transferObject() {
        if (isOpenConnected) {
            final String oh = etPtpObjectName.getText().toString();
            if (oh.trim() == "") {
                log("请输入object handle , 为数字类型");
                return;
            }
            final int ohandle = Integer.valueOf(oh);
            // final File imageLocal = getImageFile();

            (new Thread(new Runnable() {
                @Override
                public void run() {
                    File tmp = new File(getExternalCacheDir(), "tmp_" + oh + ".jpg");
                    String outputFilePath = tmp.getPath();
                    log("准备传输数据");
                    //boolean transfer = mtpDevice.importFile(ohandle,  outputFilePath);
                    try {
                        boolean transfer = bi.importFile(ohandle, outputFilePath);

                        if (transfer) {
                            log("传输成功 : " + outputFilePath);
                        } else {
                            log("传输失败");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            })).start();
        }
    }

    private final File getImageFile() {
        File appDir = new File(Environment.getExternalStorageDirectory(), "ptp_demo");
        if (!appDir.exists()) {
            appDir.mkdir();
        }

        String fileName = System.currentTimeMillis() + ".jpg";
        return (new File(appDir, fileName));
    }

    // 连接到ptp/mtp设备
    void connectMTPDevice(){
        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        Log.v("usb manager get", usbManager.toString());
        Map<String, UsbDevice> map = usbManager.getDeviceList();
        Set<String> set = map.keySet();

        if (set.size() == 0) {
            log("无法获取设备信息，请确保相机已经连接或者处于激活状态");
        }

        for (String s : set) {
            UsbDevice device = map.get(s);
            if( !usbManager.hasPermission(device) ){
                registerUsbPermission(device);
                return;
            }else {
                performConnect(device);
            }
        }
    }

    void performConnect(UsbDevice device) {
        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);

        if( !isOpenConnected ){
            //log("建立mtp设备");
            //mtpDevice = new MtpDevice(device);
            //log("mtp设备已连接");
            try {
                bi = new BaselineInitiator (device, usbManager.openDevice(device));
                // Select appropriate deviceInitiator, VIDs see http://www.linux-usb.org/usb.ids
                if (bi.device.getVendorId() == EosInitiator.CANON_VID) {
                    try {
                        bi.getClearStatus();
                        bi.close();
                    } catch (PTPException e) {e.printStackTrace();}
                    Log.d(TAG, "Device is CANON, open EOSInitiator");
                    isCanon = true;
                    isNikon = false;
                    bi = new EosInitiator(device, usbManager.openDevice(device));
                }
                else if (device.getVendorId() == NikonInitiator.NIKON_VID) {
                    try {
                        bi.getClearStatus();
                        bi.close();
                    } catch (PTPException e) {e.printStackTrace();}
                    Log.d(TAG, "Device is Nikon, open NikonInitiator");
                    isCanon = false;
                    isNikon = true;
                    bi = new NikonInitiator (device, usbManager.openDevice(device));
                }

                bi.openSession();

                isOpenConnected = true;




            } catch (PTPException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                log(e.toString());
            }

            pollReadEvent();
        } else {
            log("设备已经连接，无需重联");
        }
    }


    // searches for an interface on the given USB device, returns only class 6  // From androiddevelopers ADB-Test
    private UsbInterface findUsbInterface(UsbDevice device) {
        //Log.d (TAG, "findAdbInterface " + device.getDeviceName());
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            Log.d (TAG, "Interface " +i + " Class " +intf.getInterfaceClass() +" Prot " +intf.getInterfaceProtocol());
            if (intf.getInterfaceClass() == 6
                //255 && intf.getInterfaceSubclass() == 66 && intf.getInterfaceProtocol() == 1
                    ) {
                return intf;
            }
        }
        return null;
    }

    private void log(final String text) {
        ControllerActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, text);
                String originalText = etLogPanel.getText().toString();
                //String newText = originalText + text + "\n";
                etLogPanel.append(text + "\n");
            }
        });
    }

    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

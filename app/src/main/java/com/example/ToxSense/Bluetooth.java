package com.example.ToxSense;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.UUID;

public class Bluetooth extends AppCompatActivity {

    ImageView bild;
    Bitmap bitmap;

    TextView myLabel;
    EditText myTextbox;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    volatile boolean stopWorker;
    String command=null;
    public volatile Boolean gotImg=Boolean.FALSE;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bluetooth);
        setTitle("ToxSense");

        Button HomeButton = (Button) findViewById(R.id.HomeButton);
        Button BluetoothButton = (Button) findViewById(R.id.BluetoothButton);
        Button AQIButton = (Button) findViewById(R.id.AQIButton);

        Button openButton = (Button) findViewById(R.id.open);
        Button sendButton = (Button) findViewById(R.id.send);
        Button closeButton = (Button) findViewById(R.id.close);
        myLabel = (TextView) findViewById(R.id.label);
        myTextbox = (EditText) findViewById(R.id.entry);
        bild=findViewById(R.id.bild);

        HomeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent myIntent = new Intent(Bluetooth.this, Home.class);
                Bluetooth.this.startActivity(myIntent);
            }
        });

        BluetoothButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent myIntent = new Intent(Bluetooth.this, Bluetooth.class);
                Bluetooth.this.startActivity(myIntent);
            }
        });

        AQIButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent myIntent = new Intent(Bluetooth.this, AQI.class);
                Bluetooth.this.startActivity(myIntent);
            }
        });

        //Open Button
        openButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    findBT(myLabel);
                    openBT(myLabel,bild);
                } catch (IOException ex) {
                }
            }
        });

        //Send Button
        sendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    sendData(myLabel,command);
                } catch (IOException ex) {
                }
            }
        });

        //Close button
        closeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    closeBT(myLabel);
                } catch (IOException ex) {
                }
            }
        });
    }

    public void findBT(TextView myLabel) {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            myLabel.setText("No bluetooth adapter available");
        }

        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, 0);
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals("ToxSense")) {
                    mmDevice = device;
                    break;
                }
            }
        }
        myLabel.setText("Connection Error!");
    }

    public void openBT(TextView myLabel,ImageView bild) throws IOException {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
        mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
        mmSocket.connect();
        mmOutputStream = mmSocket.getOutputStream();
        mmInputStream = mmSocket.getInputStream();

        beginListenForData(myLabel,bild);

        myLabel.setText("Bluetooth Opened");
    }

    void beginListenForData(final TextView myLabel, final ImageView bild) {

        final Handler handler = new Handler();
        final byte delimiter = 10; //This is the ASCII code for a newline character

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[20480];
        workerThread = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                    try {
                        int bytesAvailable = mmInputStream.available();
                        if (bytesAvailable > 0) {
                            byte[] packetBytes = new byte[bytesAvailable];
                            mmInputStream.read(packetBytes);
                            for (int i = 0; i < bytesAvailable; i++) {
                                byte b = packetBytes[i];
                                if (b == delimiter) {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    ByteBuffer buf = ByteBuffer.wrap(encodedBytes);
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    //encoded_img=data;
                                    readBufferPosition = 0;

                                    byte[] imageBytes = Base64.decode(data, Base64.DEFAULT);
                                    bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);



                                    handler.post(new Runnable() {
                                        public void run() {
                                            //new Home().classifyImg(bitmap);
                                            //myLabel.setText((String)Array.get(classified,0));
                                            //new Home().classifyImg(myLabel,bild);
                                            myLabel.setText(data);

                                        }
                                    });


                                } else {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    } catch (IOException ex) {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }



    public void sendData(TextView myLabel,String command) throws IOException {
        bitmap = null;
        if(command != null){
            command+="\n";
            mmOutputStream.write(command.getBytes());
        }
        else{
        String msg = myTextbox.getText().toString();
        msg += "\n";
        mmOutputStream.write(msg.getBytes());
        myLabel.setText("Data Sent");
            }
    }

    void closeBT(TextView myLabel) throws IOException {
        stopWorker = true;
        mmOutputStream.close();
        mmInputStream.close();
        mmSocket.close();
        myLabel.setText("Bluetooth Closed");
    }
}
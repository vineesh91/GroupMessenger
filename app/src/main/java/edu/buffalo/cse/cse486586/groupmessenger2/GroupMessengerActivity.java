package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StreamCorruptedException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 *
 * @author stevko
 */
public class GroupMessengerActivity extends Activity {
    static final String TAG = GroupMessengerActivity.class.getSimpleName();
    static final String REMOTE_PORT0 = "11108";
    static final String REMOTE_PORT1 = "11112";
    static final String REMOTE_PORT2 = "11116";
    static final String REMOTE_PORT3 = "11120";
    static final String REMOTE_PORT4 = "11124";
    static final int SERVER_PORT = 10000;
    String[] remotePorts = {REMOTE_PORT0, REMOTE_PORT1, REMOTE_PORT2, REMOTE_PORT3, REMOTE_PORT4};
    int appCount = 4;
    int msgId = 0;
    int mySeqNo = 0; //used for message identifier
    int Ag = 0; //largest agreed sequence number
    int Pg = 0; //largest proposed sequence number
    HashMap<String, Integer> Rq = new HashMap<String, Integer>(); //sequence number of last delivered message from each sender
    HashMap<String, String> messagesRcvd = new HashMap<String, String>();
    List<String> agreedMessages = new ArrayList<String>(); //messages that have their sequence number confirmed
    List<String> delQueue = new ArrayList<String>(); //delievery list containing messages to be published
    String crashedPort = "";
    PriorityQueue<String> holdBackQueue;
    boolean handleExcept = false;
    boolean proposalsSent = false;
    boolean holdbackQforCrashedClrd = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);
        TextView tv = (TextView) findViewById(R.id.local_text_display);
        tv.setMovementMethod(new ScrollingMovementMethod());
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));

        TelephonyManager tel = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        crashedPort = "";
        final String myPort = String.valueOf((Integer.parseInt(portStr) * 2));
        //initializing Rq for FIFO
        for (String remoteport : remotePorts) {
            Rq.put(remoteport, 0);
        }
        final EditText editText = (EditText) findViewById(R.id.editText1);
        findViewById(R.id.button4).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = editText.getText().toString() + "\n";
                editText.setText(""); // This is one way to reset the input box.
                TextView localTextView = (TextView) findViewById(R.id.local_text_display);
                localTextView.append("\t" + msg); // This is one way to display a string.
                TextView remoteTextView = (TextView) findViewById(R.id.remote_text_display);
                remoteTextView.append("\n");
                new ClientTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, msg, myPort);
            }
        });
        //server socket
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "ServerTask failed IOException");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }

    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }
    private void removeHoldBackElem(String portCrashed) {
        if(holdBackQueue != null) {
            Iterator hbackItrtr = holdBackQueue.iterator();
            while(hbackItrtr.hasNext()) {
                String qElem = hbackItrtr.next().toString();
                if(qElem.substring(qElem.length()-5).equals(portCrashed)) {
                    holdBackQueue.remove(qElem);
                }
            }
        }
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {
        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];
            try {
                //Reference-docs.oracle.com
                Comparator<String> comp = new Comparator<String>() {
                    @Override
                    public int compare(String lhs, String rhs) {
                        int seqNo1 = Integer.parseInt(lhs.substring(0, lhs.indexOf(",")));
                        int seqNo2 = Integer.parseInt(rhs.substring(0, rhs.indexOf(",")));
                        int retVal = 0;
                        if (seqNo1 > seqNo2) {
                            retVal = 1;
                        } else if (seqNo1 == seqNo2) {
                            Log.e(TAG, "%%%%%lhs value " + lhs);
                            Log.e(TAG, "%%%%%rhs value " + rhs);
                            int id1 = Integer.parseInt(lhs.substring(lhs.indexOf(",") + 1));
                            int id2 = Integer.parseInt(rhs.substring(rhs.indexOf(",") + 1));
                            if (id1 > id2) {
                                retVal = 1;
                            } else {
                                retVal = -1;
                            }
                        } else {
                            retVal = -1;
                        }
                        return retVal;
                    }
                };
                holdBackQueue = new PriorityQueue<String>(200, comp);
                while (true) {
                    try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(2000);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String strReceived = in.readLine();
                    if (strReceived != null) {
                        if (strReceived.charAt(0) != 'A') {
                            String sendingPort = "";
                            String seqNo = "";
                            int msgLength = 0;
                            //separating out the sequence number and the message
                            for (int i = 0; i < strReceived.length(); i++) {
                                if (strReceived.charAt(i) == ',') {
                                    msgLength = i;
                                    break;
                                } else {
                                    seqNo += strReceived.charAt(i);
                                }
                            }
                            for (int i = msgLength + 1; i < strReceived.length(); i++) {
                                if (strReceived.charAt(i) == ',') {
                                    msgLength = i;
                                    break;
                                } else {
                                    sendingPort += strReceived.charAt(i);
                                }
                            }
                            String uniqueId = seqNo + sendingPort;
                            int rcvdSeqNo = Integer.parseInt(seqNo);
                            if (Ag > Pg) {
                                Pg = Ag + 1;
                                out.println(Pg);
                            } else {
                                Pg++;
                                out.println(Pg);
                            }
                            String rcvdMsg = strReceived.substring(msgLength + 1);
                            messagesRcvd.put(uniqueId, Pg + "." + seqNo + "," + rcvdMsg);
                            Log.e(TAG, "######Pg added"+ Pg+":" + uniqueId);
                            holdBackQueue.add(Pg + "," + uniqueId);
                            //publishProgress(rcvdMsg);
                        } else {
                            //from the agreement take out the agreed seq no
                            Log.e(TAG, "Agreement received for:" + strReceived);
                            String origSeq = "";
                            String senderPortNo = "";
                            int msgLength = 0;
                            for (int i = 1; i < strReceived.length(); i++) {
                                if (strReceived.charAt(i) == ',') {
                                    msgLength = i;
                                    break;
                                } else {
                                    origSeq += strReceived.charAt(i);
                                }
                            }
                            for (int i = msgLength + 1; i < strReceived.length(); i++) {
                                if (strReceived.charAt(i) == ',') {
                                    msgLength = i;
                                    break;
                                } else {
                                    senderPortNo += strReceived.charAt(i);
                                }
                            }
                            int agreedSeq = Integer.parseInt(strReceived.substring(msgLength + 1));
                            Ag = agreedSeq;
                            String uniqueId = origSeq + senderPortNo;
                            String newId = agreedSeq + "," + uniqueId;
                            if(!crashedPort.equals("") && holdbackQforCrashedClrd == false) {
                                Iterator hbackItrtr = holdBackQueue.iterator();
                                while(hbackItrtr.hasNext()) {
                                    String qElem = hbackItrtr.next().toString();
                                    if(qElem.substring(qElem.length()-5).equals(crashedPort)) {
                                        holdBackQueue.remove(qElem);
                                        Log.e(TAG,"$$$$$$removing :"+qElem);
                                    }
                                }
                                holdbackQforCrashedClrd = true;
                            }
                            if (messagesRcvd.containsKey(uniqueId)) {
                                String msg = messagesRcvd.get(uniqueId);
                                String prevId = msg.substring(0, msg.indexOf(".")) + "," + uniqueId;
                                agreedMessages.add(newId);
                                if (!prevId.equals(newId)) {
                                    Log.e(TAG, "&&&&&oldId " + prevId);
                                    boolean rmvd = holdBackQueue.remove(prevId);
                                    Log.e(TAG, "^^^^^Was removed :" + rmvd);
                                    Log.e(TAG, "******New ID " + newId);
                                    holdBackQueue.add(newId);
                                }
                                if(!crashedPort.equals("") && holdbackQforCrashedClrd == false) {
                                    Iterator hbackItrtr = holdBackQueue.iterator();
                                    while(hbackItrtr.hasNext()) {
                                        String qElem = hbackItrtr.next().toString();
                                        if(qElem.substring(qElem.length()-5).equals(crashedPort)) {
                                            holdBackQueue.remove(qElem);
                                            Log.e(TAG,"$$$$$$removing :"+qElem);
                                        }
                                    }
                                    holdbackQforCrashedClrd = true;
                                }
                                Log.e(TAG, "!!!!!!Priority queue top : " + holdBackQueue.peek());
                                while (agreedMessages.contains(holdBackQueue.peek())) {
                                    String queueContent = holdBackQueue.peek();
                                    String seqToDeliever = queueContent.substring(queueContent.indexOf(",") + 1);
                                    String msgTodlvr = messagesRcvd.get(seqToDeliever);
                                    Log.e(TAG,"Publishing the message :"+seqToDeliever);
                                    int pos1 = msgTodlvr.indexOf(".");
                                    int pos2 = msgTodlvr.indexOf(",");
                                    String pNo = msgTodlvr.substring(pos1 + 1, pos2);
                                    String sentSeq = seqToDeliever.substring(0, 1);
                                    publishProgress(msgTodlvr.substring(pos2 + 1));
                                    holdBackQueue.poll();
                                /*if (Rq.get(senderPortNo) + 1 == Integer.parseInt(sentSeq)) {
                                    Rq.put(senderPortNo, Rq.get(senderPortNo) + 1);
                                    //delQueue.add(msgTodlvr.substring(pos2 + 1));
                                    publishProgress(msgTodlvr.substring(pos2 + 1));
                                    holdBackQueue.poll();
                                }*/
                                }
                                out.println("Message sent to queue");
                            }
                        }
                    }

                    } catch(SocketTimeoutException e) {
                        Log.e(TAG,"SocketTimeOutException");

                    } catch(StreamCorruptedException e) {
                        Log.e(TAG,"StreamCorruptException");

                    } catch(EOFException e) {
                        Log.e(TAG,"EOFException");
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "ServerTask failed IOException");
                return null;
            }
        }

        protected void onProgressUpdate(String... strings) {
            /*
             * The following code displays what is received in doInBackground().
             */
            //Log.e(TAG,"*****Delqueue is empty -" +delQueue.isEmpty());
            //while (!delQueue.isEmpty()) {
            String strReceived = strings[0];
            TextView remoteTextView = (TextView) findViewById(R.id.remote_text_display);
            remoteTextView.append(strReceived + "\t\n");
            TextView localTextView = (TextView) findViewById(R.id.local_text_display);
            localTextView.append("\n");
            /*
             * The following code creates a file in the AVD's internal storage and stores a file.
             *
             * For more information on file I/O on Android, please take a look at
             * http://developer.android.com/training/basics/data-storage/files.html

             */
            ContentValues cval = new ContentValues();
            cval.put("key", Integer.toString(msgId));
            cval.put("value", strReceived);
            msgId++;
            Uri uri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
            getContentResolver().insert(uri, cval);
            //delQueue.remove(0);
            String filename = "GroupMessenger2Output";
            String string = strReceived + "\n";
            FileOutputStream outputStream;
            try {
                outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
                outputStream.write(string.getBytes());
                outputStream.close();
            } catch (Exception e) {
                Log.e(TAG, "File write failed");
            }
            return;
        }
    }

    private class ClientTask extends AsyncTask<String, String[], Void> {
        @Override
        protected Void doInBackground(String... msgs) {
            String portTimedOut = ""; //used to find the port that times out
            int maxProposal = 0;
            String prop;
            String msgToSend = msgs[0];
            int seqToSend = ++mySeqNo; //unique identifier here will be mySeqNo+port no
            //sending msg with uniqueIdentifier
            msgToSend = seqToSend + "," + msgs[1] + "," + msgToSend;
            Log.e(TAG, "@@@@@@Sending message  :" + msgToSend);
            Socket socket =null;
            PrintWriter out = null;
            BufferedReader in =null;
            try {

                proposalsSent = false;

                if(!crashedPort.equals(REMOTE_PORT0)) {
                    portTimedOut = REMOTE_PORT0;
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT0));
                    socket.setSoTimeout(2000);
                    //Reference-docs.oracle.com
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    out.println(msgToSend);
                    prop = in.readLine();
                    if(prop == null) {
                        crashedPort = REMOTE_PORT0;
                        //removeHoldBackElem(crashedPort);
                    }
                    else {
                        int proposal1 = Integer.parseInt(prop);
                        if(proposal1 > maxProposal) {
                            maxProposal = proposal1;
                        }
                        Log.e(TAG,"++++++Prop sent from"+REMOTE_PORT0+" for "+msgToSend+"is "+proposal1+" & max: "+maxProposal);
                    }
                    out.close();
                    in.close();
                    socket.close();
                }

                if(!crashedPort.equals(REMOTE_PORT1)) {
                    portTimedOut = REMOTE_PORT1;
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT1));
                    socket.setSoTimeout(2000);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    out.println(msgToSend);
                    prop = in.readLine();
                    if(prop == null) {
                        crashedPort = REMOTE_PORT1;
                        //removeHoldBackElem(crashedPort);
                    }
                    else {
                        int proposal2 = Integer.parseInt(prop);
                        if(proposal2 > maxProposal) {
                            maxProposal = proposal2;
                        }
                        Log.e(TAG,"++++++Prop sent from"+REMOTE_PORT1+" for "+msgToSend+"is "+proposal2+" & max: "+maxProposal);
                    }
                    out.close();
                    in.close();
                    socket.close();
                }
                if(!crashedPort.equals(REMOTE_PORT2)) {
                    portTimedOut = REMOTE_PORT2;
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT2));
                    socket.setSoTimeout(2000);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    out.println(msgToSend);
                    prop = in.readLine();
                    if(prop == null) {
                        crashedPort = REMOTE_PORT2;
                        //removeHoldBackElem(crashedPort);
                    }
                    else {
                        int proposal3 = Integer.parseInt(prop);
                        if(proposal3 > maxProposal) {
                            maxProposal = proposal3;
                        }
                        Log.e(TAG,"++++++Prop sent from"+REMOTE_PORT2+" for "+msgToSend+"is "+proposal3+" & max: "+maxProposal);
                    }
                    out.close();
                    in.close();
                    socket.close();
                }
                if(!crashedPort.equals(REMOTE_PORT3)) {
                    portTimedOut = REMOTE_PORT3;
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT3));
                    socket.setSoTimeout(2000);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    out.println(msgToSend);
                    prop = in.readLine();
                    if(prop == null) {
                        crashedPort = REMOTE_PORT3;
                        //removeHoldBackElem(crashedPort);
                    }
                    else {
                        int proposal4 = Integer.parseInt(prop);
                        if(proposal4 > maxProposal) {
                            maxProposal = proposal4;
                        }
                        Log.e(TAG,"++++++Prop sent from"+REMOTE_PORT3+" for "+msgToSend+"is "+proposal4+" & max: "+maxProposal);
                    }

                    out.close();
                    in.close();
                    socket.close();
                }

                if(!crashedPort.equals(REMOTE_PORT4)) {
                    portTimedOut = REMOTE_PORT4;
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT4));
                    socket.setSoTimeout(2000);
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    out.println(msgToSend);
                    prop = in.readLine();
                    if(prop == null) {
                        crashedPort = REMOTE_PORT4;
                        //removeHoldBackElem(crashedPort);
                    }
                    else {
                        int proposal5 = Integer.parseInt(prop);
                        if(proposal5 > maxProposal) {
                            maxProposal = proposal5;
                        }
                        Log.e(TAG,"++++++Prop sent from"+REMOTE_PORT4+" for "+msgToSend+"is "+proposal5+" & max: "+maxProposal);
                    }
                    out.close();
                    in.close();
                    socket.close();
                }

                Log.e(TAG,"!!!!!!!Proposals were recieved");
                proposalsSent = true;
                String[] pubMsgs = {Integer.toString(maxProposal), msgs[1], Integer.toString(seqToSend)};
                //onProgressUpdate(pubMsgs);
                for (String remotePort : remotePorts) {
                    if(!remotePort.equals(crashedPort)) {
                        portTimedOut = remotePort;
                        socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(remotePort));
                        socket.setSoTimeout(2000);
                        out = new PrintWriter(socket.getOutputStream(), true);
                        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        Log.e(TAG, "Conf for" + "A" + pubMsgs[2] + "," + pubMsgs[1] + "," + pubMsgs[0]+"sent to :"+remotePort);
                        out.println("A" + pubMsgs[2] + "," + pubMsgs[1] + "," + pubMsgs[0]);
                        in.readLine();
                        out.close();
                        in.close();
                        socket.close();
                    }
                }
            }catch (SocketTimeoutException e) {
                Log.e(TAG, "SocketTimeoutException"+portTimedOut);
                try {
                    out.close();
                    in.close();
                    socket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                crashedPort = portTimedOut;
                handleExcept = true;
                //removeHoldBackElem(crashedPort);
            }
            catch (StreamCorruptedException e) {
                handleExcept = true;
                Log.e(TAG, "StreamCorrupted"+portTimedOut);
                try {
                    out.close();
                    in.close();
                    socket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                crashedPort = portTimedOut;
                //removeHoldBackElem(crashedPort);
            }
            catch (EOFException e) {
                handleExcept = true;
                Log.e(TAG, "EOF exception"+portTimedOut);
                try {
                    out.close();
                    in.close();
                    socket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                crashedPort = portTimedOut;
                //removeHoldBackElem(crashedPort);
            }
            catch (UnknownHostException e) {
                //handleExcept = true;
                Log.e(TAG, "ClientTask UnknownHostException");
            } catch (IOException e) {
                handleExcept =true;
                Log.e(TAG, "ClientTask socket IOException "+portTimedOut);
                try {
                    out.close();
                    in.close();
                    socket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                crashedPort = portTimedOut;
                //removeHoldBackElem(crashedPort);
            } finally {
                if(handleExcept == true) {
                    handleExcept =false;
                    try {
                    if(proposalsSent ==false) {
                        for (String remotePort : remotePorts) {
                            if (Integer.parseInt(remotePort) > Integer.parseInt(crashedPort)) {
                                portTimedOut = remotePort;
                                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(remotePort));
                                socket.setSoTimeout(2000);
                                out = new PrintWriter(socket.getOutputStream(), true);
                                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                                out.println(msgToSend);
                                prop = in.readLine();
                                int proposal = Integer.parseInt(prop);
                                if (proposal > maxProposal) {
                                    maxProposal = proposal;
                                }
                                Log.e(TAG,"++++++Prop sent from"+remotePort+" for "+msgToSend+"is "+proposal+" & max: "+maxProposal);
                                out.close();
                                in.close();
                                socket.close();
                            }
                        }

                    }
                            String[] pubMsgs = {Integer.toString(maxProposal), msgs[1], Integer.toString(seqToSend)};
                            //onProgressUpdate(pubMsgs);
                            for (String remPrt : remotePorts) {
                                if ((proposalsSent == false &&!remPrt.equals(crashedPort))|| (proposalsSent ==true && Integer.parseInt(remPrt) > Integer.parseInt(crashedPort))) {
                                    portTimedOut = remPrt;
                                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(remPrt));
                                    socket.setSoTimeout(2000);
                                    out = new PrintWriter(socket.getOutputStream(), true);
                                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                                    Log.e(TAG, "Conf for" + "A" + pubMsgs[2] + "," + pubMsgs[1] + "," + pubMsgs[0]+"sent to:"+remPrt);
                                    out.println("A" + pubMsgs[2] + "," + pubMsgs[1] + "," + pubMsgs[0]);
                                    in.readLine();
                                    out.close();
                                    in.close();
                                    socket.close();
                                }


                    }
                        proposalsSent = true;
                        } catch (UnknownHostException e) {
                        } catch (IOException e) {
                        }
                    }
                }

            return null;
        }

        protected void onProgressUpdate(String... strings) {
            //new ConfirmationTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, strings[0], strings[1], strings[2]);
            return;
        }
    }

}
/*
 * Copyright (C) 2011, The Android Open Source Project
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
/*
 * Contributed by: Giesecke & Devrient GmbH.
 */

package org.simalliance.openmobileapi.service;

import android.content.Context;

import org.simalliance.openmobileapi.service.ISmartcardServiceCallback;
import org.simalliance.openmobileapi.service.SmartcardService.SmartcardServiceSession;

import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;


import android.content.pm.PackageManager;
import org.simalliance.openmobileapi.service.security.AccessControlEnforcer;
import org.simalliance.openmobileapi.service.security.ChannelAccess;


/**
 * Smartcard service base class for terminal resources.
 */
public abstract class Terminal implements ITerminal {

    /** Random number generator used for handle creation. */
    static Random mRandom = new Random();

    protected Context mContext;

    private final Map<Long, IChannel> mChannels = new HashMap<Long, IChannel>();

    private final Object mLock = new Object();

    protected final String mName;

    public volatile boolean mIsConnected;

    protected byte[] mSelectResponse;

    protected boolean mDefaultApplicationSelectedOnBasicChannel = true;


    /**
     * For each Terminal there will be one AccessController object.
     */
    private AccessControlEnforcer mAccessControlEnforcer;


   /**
     * Returns a concatenated response.
     *
     * @param r1 the first part of the response.
     * @param r2 the second part of the response.
     * @param length the number of bytes of the second part to be appended.
     * @return a concatenated response.
     */
    static byte[] appendResponse(byte[] r1, byte[] r2, int length) {
        byte[] rsp = new byte[r1.length + length];
        System.arraycopy(r1, 0, rsp, 0, r1.length);
        System.arraycopy(r2, 0, rsp, r1.length, length);
        return rsp;
    }

    /**
     * Creates a formatted exception message.
     *
     * @param commandName the name of the command. <code>null</code> if not
     *            specified.
     * @param sw the response status word.
     * @return a formatted exception message.
     */
    static String createMessage(String commandName, int sw) {
        StringBuffer message = new StringBuffer();
        if (commandName != null) {
            message.append(commandName).append(" ");
        }
        message.append("SW1/2 error: ");
        message.append(Integer.toHexString(sw | 0x10000).substring(1));
        return message.toString();
    }

    /**
     * Creates a formatted exception message.
     *
     * @param commandName the name of the command. <code>null</code> if not
     *            specified.
     * @param message the message to be formatted.
     * @return a formatted exception message.
     */
    static String createMessage(String commandName, String message) {
        if (commandName == null) {
            return message;
        }
        return commandName + " " + message;
    }

    public Terminal(String name, Context context) {
        this.mContext = context;
        this.mName = name;
    }

    /**
     * This method is called in SmartcardService:onDestroy
     * to clean up all open channels
     */
    public synchronized void closeChannels() {
        Collection<IChannel> col = mChannels.values();
        IChannel[] channelList = col.toArray(new IChannel[col.size()]);
        for (IChannel channel : channelList) {
            try {
                closeChannel((Channel)channel);
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * Closes the specified channel.
     *
     * @param channel the channel to be closed.
     * @throws CardException if closing the channel failed.
     */
    public void closeChannel(Channel channel) throws CardException {

        synchronized(mLock) {
            try {
                internalCloseLogicalChannel(channel.getChannelNumber());
            } finally {
                mChannels.remove(channel.getHandle());
                if (mIsConnected && mChannels.isEmpty()) {
                    try {
                        internalDisconnect();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /**
     * Creates a channel instance.
     *
     * @param channelNumber the channel number according to ISO 7816-4.
     * @param callback the callback used to detect the death of the client.
     * @return a channel instance.
     */
    protected Channel createChannel(SmartcardServiceSession session, int channelNumber, ISmartcardServiceCallback callback) {
        return new Channel(session, this, channelNumber, callback);
    }

    private IChannel getBasicChannel() {
        for (IChannel channel : mChannels.values()) {
            if (channel.getChannelNumber() == 0) {
                return channel;
            }
        }
        return null;
    }

    public synchronized IChannel getChannel(long hChannel) {
        return mChannels.get(hChannel);
    }

    public String getName() {
        return mName;
    }

    /**
     * Implements the terminal specific connect operation.
     *
     * @throws CardException if connecting the card failed.
     */
    protected abstract void internalConnect() throws CardException;

    /**
     * Implements the terminal specific disconnect operation.
     *
     * @throws CardException if disconnecting from the card failed.
     */
    protected abstract void internalDisconnect() throws CardException;

    /**
     * Implementation of the SELECT command.
     *
     * @return the number of the logical channel according to ISO 7816-4.
     * @throws CardException
     */
    abstract protected int internalOpenLogicalChannel() throws Exception;

    /**
     * Implementation of the MANAGE CHANNEL open and SELECT commands.
     *
     * @return the number of the logical channel according to ISO 7816-4.
     * @throws CardException
     */
    abstract protected int internalOpenLogicalChannel(byte[] aid) throws Exception;

    /**
     * Implementation of the MANAGE CHANNEL close command.
     *
     * @param channelNumber
     * @throws CardException
     */
    abstract protected void internalCloseLogicalChannel(int channelNumber) throws CardException;

    /**
     * Implements the terminal specific transmit operation.
     *
     * @param command the command APDU to be transmitted.
     * @return the response APDU received.
     * @throws CardException if the transmit operation failed.
     */
    protected abstract byte[] internalTransmit(byte[] command) throws CardException;

    /**
     * Performs a select command on the basic channel without an AID parameter. <br>
     * The card manager will be selected.
     */
    public void select() {
        mSelectResponse = null;
        byte[] selectCommand = new byte[5];
        selectCommand[0] = 0x00;
        selectCommand[1] = (byte) 0xA4;
        selectCommand[2] = 0x04;
        selectCommand[3] = 0x00;
        selectCommand[4] = 0x00;
        try {
            mSelectResponse = transmit(selectCommand, 2, 0x9000, 0xFFFF, "SELECT");

        } catch (Exception exp) {
            throw new NoSuchElementException(exp.getMessage());
        }
    }

    /**
     * Returns the ATR of the connected card or null if the ATR is not
     * available.
     *
     * @return the ATR of the connected card or null if the ATR is not
     *         available.
     */
    public byte[] getAtr() {
        return null;
    }

    /**
     * Performs a select command on the basic channel
     *
     * @param aid the aid which should be selected.
     */
    public void select(byte[] aid) {
        if (aid == null) {
            throw new NullPointerException("aid must not be null");
        }
        mSelectResponse = null;
        byte[] selectCommand = new byte[aid.length + 6];
        selectCommand[0] = 0x00;
        selectCommand[1] = (byte) 0xA4;
        selectCommand[2] = 0x04;
        selectCommand[3] = 0x00;
        selectCommand[4] = (byte) aid.length;
        System.arraycopy(aid, 0, selectCommand, 5, aid.length);
        try {
            mSelectResponse = transmit(selectCommand, 2, 0x9000, 0xFFFF, "SELECT");
        } catch (Exception exp) {
            throw new NoSuchElementException(exp.getMessage());
        }
    }

    public Channel openBasicChannel(SmartcardServiceSession session, ISmartcardServiceCallback callback) throws CardException {
        if (callback == null) {
            throw new NullPointerException("callback must not be null");
        }

        synchronized (mLock) {
            if(!mDefaultApplicationSelectedOnBasicChannel) {
                throw new CardException("default application is not selected");
            }
            if (getBasicChannel() != null) {
                throw new CardException("basic channel in use");
            }
            if (mChannels.isEmpty()) {
                internalConnect();
            }


            Channel basicChannel = createChannel(session, 0, callback);
            basicChannel.hasSelectedAid(false, null);
            registerChannel(basicChannel);
            return basicChannel;
        }
    }

    public Channel openBasicChannel(SmartcardServiceSession session, byte[] aid, ISmartcardServiceCallback callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("callback must not be null");
        }
        if (aid == null) {
            throw new NullPointerException("aid must not be null");
        }

        synchronized (mLock) {
            if (getBasicChannel() != null) {
                throw new CardException("basic channel in use");
            }
            if (mChannels.isEmpty()) {
                internalConnect();
            }

            try {
                select(aid);
            } catch (Exception e) {
                if (mIsConnected && mChannels.isEmpty()) {
                    internalDisconnect();
                }
                throw e;
            }


            Channel basicChannel = createChannel(session, 0, callback);
            basicChannel.hasSelectedAid(true, aid);
            mDefaultApplicationSelectedOnBasicChannel = false;
            registerChannel(basicChannel);
            return basicChannel;
        }
    }

    public Channel openLogicalChannel(SmartcardServiceSession session, ISmartcardServiceCallback callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("callback must not be null");
        }

        synchronized (mLock) {
            if (mChannels.isEmpty()) {
                internalConnect();
            }

            int channelNumber = 0;
            try {
                channelNumber = internalOpenLogicalChannel();
            } catch (Exception e) {
                if (mIsConnected && mChannels.isEmpty()) {
                    internalDisconnect();
                }
                throw e;
            }


            Channel logicalChannel = createChannel(session, channelNumber, callback);
            logicalChannel.hasSelectedAid(false, null);
            registerChannel(logicalChannel);
            return logicalChannel;
        }
    }

    public Channel openLogicalChannel(SmartcardServiceSession session, byte[] aid, ISmartcardServiceCallback callback) throws Exception {
        if (callback == null) {
            throw new NullPointerException("callback must not be null");
        }
        if (aid == null) {
            throw new NullPointerException("aid must not be null");
        }

        synchronized (mLock) {
            if (mChannels.isEmpty()) {
                internalConnect();
            }

            int channelNumber = 0;
            try {
                channelNumber = internalOpenLogicalChannel(aid);
            } catch (Exception e) {
                if (mIsConnected && mChannels.isEmpty()) {
                    internalDisconnect();
                }
                throw e;
            }


            Channel logicalChannel = createChannel(session, channelNumber, callback);
            logicalChannel.hasSelectedAid(true, aid);
            registerChannel(logicalChannel);
            return logicalChannel;
        }
    }

    public boolean isConnected() {
        return mIsConnected;
    }

    /**
     * Protocol specific implementation of the transmit operation. This method
     * is synchronized in order to handle GET RESPONSE and command repetition
     * without interruption by other commands.
     *
     * @param cmd the command to be transmitted.
     * @return the response received.
     * @throws CardException if the transmit operation failed.
     */
    protected synchronized byte[] protocolTransmit(byte[] cmd) throws CardException {
        byte[] command = cmd;
        byte[] rsp = null;
        synchronized (mLock) {
            rsp = internalTransmit(command);
        }

        //don't display NullPointerException
        if (rsp == null) {
            throw new CardException("no response data");
        }

        if (rsp.length >= 2) {
            int sw1 = rsp[rsp.length - 2] & 0xFF;
            int sw2 = rsp[rsp.length - 1] & 0xFF;
            if (sw1 == 0x6C) {
                command[cmd.length - 1] = rsp[rsp.length - 1];
                rsp = internalTransmit(command);
            } else if (sw1 == 0x61) {
                byte[] getResponseCmd = new byte[] {
                        command[0], (byte) 0xC0, 0x00, 0x00, 0x00
                };
                byte[] response = new byte[rsp.length - 2];
                System.arraycopy(rsp, 0, response, 0, rsp.length - 2);
                while (true) {
                    getResponseCmd[4] = rsp[rsp.length - 1];
                    rsp = internalTransmit(getResponseCmd);
                    if (rsp.length >= 2 && rsp[rsp.length - 2] == 0x61) {
                        response = appendResponse(response, rsp, rsp.length - 2);
                    } else {
                        response = appendResponse(response, rsp, rsp.length);
                        break;
                    }
                }
                rsp = response;
/*
            } else if (rsp.length == 2 && sw1 == 0x63 && sw2 == 0x10) {
                byte[] getResponseCmd = new byte[] {
                        (byte)(command[0] & 0x03), (byte) 0xC0, 0x00, 0x00, 0x00
                };
                byte[] response = new byte[rsp.length - 2];
                System.arraycopy(rsp, 0, response, 0, rsp.length - 2);
                rsp[rsp.length - 1] = 0x00;
                while (true) {
                    getResponseCmd[4] = rsp[rsp.length - 1];
                    rsp = internalTransmit(getResponseCmd);
                    if (rsp.length >= 2 && rsp[rsp.length - 2] == 0x61) {
                        response = appendResponse(response, rsp, rsp.length - 2);
                    } else {
                        response = appendResponse(response, rsp, rsp.length);
                        break;
                    }
                }
                rsp = response;
*/
            }
        }
        return rsp;
    }

    /**
     * Creates a handle for the specified channel instances and adds the channel
     * instance to the channel list.
     *
     * @param channel
     * @return the channel handle.
     */
    private long registerChannel(Channel channel) {
        long hChannel = mRandom.nextInt();
        hChannel <<= 32;
        hChannel |= (((long) channel.hashCode()) & 0xFFFFFFFFL);

        channel.setHandle(hChannel);

        mChannels.put(hChannel, channel);

        return hChannel;
    }

    /**
     * Transmits the specified command and returns the response. Optionally
     * checks the response length and the response status word. The status word
     * check is implemented as follows (sw = status word of the response):
     * <p>
     * if ((sw & swMask) != (swExpected & swMask)) throw new CardException();
     * </p>
     *
     * @param cmd the command APDU to be transmitted.
     * @param minRspLength the minimum length of received response to be
     *            checked.
     * @param swExpected the response status word to be checked.
     * @param swMask the mask to be used for response status word comparison.
     * @param commandName the name of the smart card command for logging
     *            purposes. May be <code>null</code>.
     * @return the response received.
     * @throws CardException if the transmit operation or the minimum response
     *             length check or the status word check failed.
     */
    public byte[] transmit(byte[] cmd, int minRspLength, int swExpected, int swMask,
        String commandName) throws CardException {
        byte[] rsp = null;
        try {
            rsp = protocolTransmit(cmd);
        } catch (CardException e) {
            if (commandName == null) {
                throw e;
            } else {
                throw new CardException(createMessage(commandName, "transmit failed"), e);
            }
        }
        if (minRspLength > 0) {
            if (rsp == null || rsp.length < minRspLength) {
                throw new CardException(createMessage(commandName, "response too small"));
            }
        }
        if (swMask != 0) {
            if (rsp == null || rsp.length < 2) {
                throw new CardException(createMessage(commandName, "SW1/2 not available"));
            }
            int sw1 = rsp[rsp.length - 2] & 0xFF;
            int sw2 = rsp[rsp.length - 1] & 0xFF;
            int sw = (sw1 << 8) | sw2;
            if ((sw & swMask) != (swExpected & swMask)) {
                throw new CardException(createMessage(commandName, sw));
            }
        }
        return rsp;
    }

    /**
     * Returns the data as received from the application select command inclusively the status word.
     * The returned byte array contains the data bytes in the following order:
     * [<first data byte>, ..., <last data byte>, <sw1>, <sw2>]
     * @return The data as returned by the application select command inclusively the status word.
     * @return Only the status word if the application select command has no returned data.
     * @return null if an application select command has not been performed or the selection response can not
     * be retrieved by the reader implementation.
     */
    public byte[] getSelectResponse()
    {
        return mSelectResponse;
    }

    /**
     * Exchanges APDU (SELECT, READ/WRITE) to the
     * given EF by File ID and file path via iccIO.
     *
     * The given command is checked and might be rejected.
     *
     * @param fileID
     * @param filePath
     * @param cmd
     * @return
     */
    public byte[] simIOExchange(int fileID, String filePath, byte[] cmd)
        throws Exception {
        throw new Exception("SIM IO error!");
    }



    public ChannelAccess setUpChannelAccess(
            PackageManager packageManager,
            byte[] aid,
            String packageName,
            ISmartcardServiceCallback callback){
        if( mAccessControlEnforcer == null ){
            throw new AccessControlException("Access Control Enforcer not properly set up");
        }
        mAccessControlEnforcer.setPackageManager(packageManager);
        return mAccessControlEnforcer.setUpChannelAccess(aid, packageName, callback);
    }

    public synchronized boolean initializeAccessControl(boolean loadAtStartup, ISmartcardServiceCallback callback ){
        if( mAccessControlEnforcer == null ){
            mAccessControlEnforcer = new AccessControlEnforcer(this);
        }
        return mAccessControlEnforcer.initialize( loadAtStartup, callback );
    }

    public AccessControlEnforcer getAccessControlEnforcer(){
        return mAccessControlEnforcer;
    }

    public synchronized void resetAccessControl() {
        if(mAccessControlEnforcer != null ) mAccessControlEnforcer.reset();
    }


    /**
     * Implementation of the SmartcardService Reader interface according to OMAPI.
     */
    final class SmartcardServiceReader extends ISmartcardServiceReader.Stub {

        protected final SmartcardService mService;

        private final ArrayList<SmartcardServiceSession> mSessions = new ArrayList<SmartcardServiceSession>();

        private final Object mLock = new Object();

        public SmartcardServiceReader( SmartcardService service ){
            this.mService = service;
        }

        public byte[] getAtr(){
            return Terminal.this.getAtr();
        }

        @Override
        public String getName(SmartcardError error) throws RemoteException {
            SmartcardService.clearError(error);
            return Terminal.this.getName();
        }

        @Override
        public boolean isSecureElementPresent(SmartcardError error)
                throws RemoteException {
            SmartcardService.clearError(error);
            try {
                return Terminal.this.isCardPresent();
            } catch (Exception e) {
                SmartcardService.setError(error, e);
            }
            return false;
        }

        @Override
        public ISmartcardServiceSession openSession(SmartcardError error)
                throws RemoteException {
            SmartcardService.clearError(error);
            try {
                if (!Terminal.this.isCardPresent()){
                                    throw new RemoteException ("Secure Element is not presented.");
                }
            } catch (CardException e) {
                SmartcardService.setError(error,e);
                return null;
            }

            synchronized (mLock) {
                try {
                    mService.initializeAccessControl(Terminal.this.getName(), null);
                } catch (Exception e ){
                    SmartcardService.setError(error,e);
                    return null; // Reader.openSession() will throw an IOException when session is null
                }
                SmartcardServiceSession session = mService.new SmartcardServiceSession(this);
                mSessions.add(session);

                return session;
            }
        }

        @Override
        public void closeSessions(SmartcardError error) throws RemoteException {

            SmartcardService.clearError(error);
            synchronized (mLock) {
                for (SmartcardServiceSession session : mSessions) {
                    if (session != null && !session.isClosed()) {
                        session.closeChannels(error);
                        session.setClosed();
                    }
                }
                mSessions.clear();
            }
        }

        /**
         * Closes the defined Session and all its allocated resources. <br>
         * After calling this method the Session can not be used for the
         * communication with the Secure Element any more.
         *
         * @param session the Session that should be closed
         * @throws RemoteException
         * @throws CardException
         * @throws NullPointerException if Session is null
         */
        void closeSession(SmartcardServiceSession session) throws RemoteException, CardException {
            if (session == null) {
                throw new NullPointerException("session is null");
            }
            synchronized (mLock) {
                if (!session.isClosed()) {
                    SmartcardError error = new SmartcardError();
                    session.closeChannels(error);
                    error.throwException();
                    session.setClosed();
                }
                mSessions.remove(session);
            }
        }

        Terminal getTerminal() {
            return Terminal.this;
        }
    }

    public void dump(PrintWriter writer, String prefix) {
            writer.println(prefix + "SMARTCARD SERVICE TERMINAL: " + mName);
            writer.println();

            prefix += "  ";

            writer.println(prefix + "mIsConnected:" + mIsConnected);
            writer.println();

            /* Dump the list of currunlty openned channels */
            writer.println(prefix + "List of open channels:");

            for(IChannel channel: mChannels.values()) {
                writer.println(prefix + "  channel " + channel.getChannelNumber() + ": ");
                writer.println(prefix + "    package      : " + channel.getChannelAccess().getPackageName());
                writer.println(prefix + "    pid          : " + channel.getChannelAccess().getCallingPid());
                writer.println(prefix + "    aid selected : " + channel.hasSelectedAid());
                writer.println(prefix + "    basic channel: " + channel.isBasicChannel());
            }

            writer.println();

            /* Dump ACE data */
            if(mAccessControlEnforcer != null) mAccessControlEnforcer.dump(writer, prefix);
        }
}

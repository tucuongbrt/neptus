/*
 * Copyright (c) 2004-2014 Universidade do Porto - Faculdade de Engenharia
 * Laboratório de Sistemas e Tecnologia Subaquática (LSTS)
 * All rights reserved.
 * Rua Dr. Roberto Frias s/n, sala I203, 4200-465 Porto, Portugal
 *
 * This file is part of Neptus, Command and Control Framework.
 *
 * Commercial Licence Usage
 * Licencees holding valid commercial Neptus licences may use this file
 * in accordance with the commercial licence agreement provided with the
 * Software or, alternatively, in accordance with the terms contained in a
 * written agreement between you and Universidade do Porto. For licensing
 * terms, conditions, and further information contact lsts@fe.up.pt.
 *
 * European Union Public Licence - EUPL v.1.1 Usage
 * Alternatively, this file may be used under the terms of the EUPL,
 * Version 1.1 only (the "Licence"), appearing in the file LICENSE.md
 * included in the packaging of this file. You may not use this work
 * except in compliance with the Licence. Unless required by applicable
 * law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific
 * language governing permissions and limitations at
 * https://www.lsts.pt/neptus/licence.
 *
 * For more information please see <http://lsts.fe.up.pt/neptus>.
 *
 * Author: zp
 * Jun 28, 2013
 */
package pt.lsts.neptus.comm.iridium;

import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Vector;

import pt.lsts.imc.IMCMessage;
import pt.lsts.imc.IridiumMsgRx;
import pt.lsts.imc.IridiumMsgTx;
import pt.lsts.imc.IridiumTxStatus;
import pt.lsts.neptus.NeptusLog;
import pt.lsts.neptus.comm.manager.imc.ImcMsgManager;
import pt.lsts.neptus.comm.manager.imc.ImcSystem;
import pt.lsts.neptus.comm.manager.imc.ImcSystemsHolder;
import pt.lsts.neptus.messages.TypedMessageFilter;
import pt.lsts.neptus.messages.listener.MessageInfo;
import pt.lsts.neptus.messages.listener.MessageListener;

/**
 * @author zp
 * 
 */
public class DuneIridiumMessenger implements IridiumMessenger, MessageListener<MessageInfo, IMCMessage> {

    boolean available = false;
    protected String messengerName;
    protected int req_id = (int) (Math.random() * 65535);

    protected Vector<IridiumMessage> messagesReceived = new Vector<>();

    protected HashSet<IridiumMessageListener> listeners = new HashSet<>();
    
    @Override
    public void addListener(IridiumMessageListener listener) {
        listeners.add(listener);
    }
    
    @Override
    public void removeListener(IridiumMessageListener listener) {
        listeners.remove(listener);       
    }
    
    public DuneIridiumMessenger(String messengerName) {
        this.messengerName = messengerName;
        ImcMsgManager.getManager().addListener(this, messengerName,
                new TypedMessageFilter(
                        IridiumMsgRx.class.getSimpleName(), 
                        IridiumTxStatus.class.getSimpleName()));
    }
    
    @Override
    public void onMessage(MessageInfo info, IMCMessage msg) {
        if (msg.getMgid() == IridiumMsgRx.ID_STATIC) {
            try {
                IridiumMessage m = IridiumMessage.deserialize(msg.getRawData("data"));
                messagesReceived.add(m);
                NeptusLog.pub().info("Received a "+m.getClass().getSimpleName()+" from "+msg.getSourceName());
                for (IridiumMessageListener listener : listeners)
                    listener.messageReceived(m);
            }
            catch (Exception e) {
                NeptusLog.pub().error(e);
            }            
        }
        else if (msg.getMgid() == IridiumTxStatus.ID_STATIC) {
            //TODO
        }
    }

    @Override
    public void sendMessage(IridiumMessage msg) throws Exception {
        
        // Activate and deactivate subscriptions should use the id of the used gateway
        if (msg instanceof ActivateSubscription || msg instanceof DeactivateSubscription) {
            ImcSystem system = ImcSystemsHolder.lookupSystemByName(messengerName);
            if (system != null)
                msg.setSource(system.getId().intValue());
        }
        
        IridiumMsgTx tx = new IridiumMsgTx();
        tx.setReqId((++req_id % 65535));
        tx.setTtl(3600);
        tx.setData(msg.serialize());
        if (!ImcMsgManager.getManager().sendMessageToSystem(tx, messengerName))
            throw new Exception("Error while sending message to " + messengerName + " via IMC.");
    }

    @Override
    public Collection<IridiumMessage> pollMessages(Date timeSince) throws Exception {
        return new Vector<>();
    }
    
    @Override
    public String getName() {
        return "DUNE Iridium Messenger ("+messengerName+")";
    }

    /**
     * @return the messengerName
     */
    public String getMessengerName() {
        return messengerName;
    }

    @Override
    public boolean isAvailable() {
        //System.out.println(System.currentTimeMillis() - ImcMsgManager.getManager().getState(messengerName).lastAnnounce().getTimestampMillis() < 60000);
        return true;
        //ImcSystem sys = ImcSystemsHolder.lookupSystemByName(messengerName);
        //if (sys == null)
        //    return false;
        //return (System.currentTimeMillis() - sys.getLastErrorStateReceived()) < 60000;
    }
    
    @Override
    public void cleanup() {
        listeners.clear();
        messagesReceived.clear();
    }

}

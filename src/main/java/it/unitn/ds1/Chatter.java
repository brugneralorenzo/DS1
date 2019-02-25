package it.unitn.ds1;

import akka.actor.ActorRef;
import akka.actor.AbstractActor;

import java.sql.Time;
import java.util.*;
import java.io.Serializable;

import akka.actor.Props;

import java.lang.Thread;
import java.lang.InterruptedException;
import java.util.concurrent.TimeUnit;

//import jdk.internal.cmm.SystemResourcePressureImpl;
import scala.concurrent.duration.Duration;

class Chatter extends AbstractActor {

    private Random rnd = new Random();
    private int sendCount = 0;    // number of sent messages
    private int id;    // ID of the current actor
    private int viewId = 0;    //the ID of the view
    private final List<Integer> listId = new ArrayList<>();
    private int inhibit_sends = 0;
    private final List<ChatMsg> delivered = new ArrayList<>();
    private int lastViewToBeInstalled = 0;
    private final List<Groups> groups = new ArrayList<>();
    private List<ActorRef> intersectionListId = new ArrayList<>();
    private final List<ActorRef> receivedFlush = new ArrayList<>();
    private final static int BEACON_INTERVAL = 5000;


    // a buffer storing all received chat messages
    private StringBuffer chatHistory = new StringBuffer();
    // message queue to hold out-of-order messages
    private List<ChatMsg> mq = new ArrayList<>();

    /* -- Message types ------------------------------------------------------- */

    // Start message that informs every chat participant about its peers
    public static class JoinGroupMsg implements Serializable {
        private final int id;
        private final Groups groups;

        public JoinGroupMsg(int id, Groups groups) {
            this.id = id;
            this.groups = groups;
        }
    }

    public static class Groups {
        private int viewId;
        private List<Integer> listId;
        private List<ActorRef> group;

        public Groups(int viewId, List<Integer> listId, List<ActorRef> group) {
            this.viewId = viewId;
            this.listId = listId;
            this.group = group;
        }
    }

    // A message requesting the peer to start a discussion on his topic
    public static class StartChatMsg implements Serializable {
        private final String messageString;

        public StartChatMsg(String messageString){
            this.messageString = messageString;
        }
    }

    public static class RequestJoin implements Serializable {
    }

    public static class ViewMessage implements Serializable {
        private final Groups groups;

        public ViewMessage(Groups groups) {
            this.groups = groups;
        }
    }

    public static class Timeout implements Serializable {

    }


    // Chat message
    public static class ChatMsg implements Serializable {
        private final String id;      // the ID of the message composed by message ID and sender ID
        private final int senderId;   // the ID of the message sender
        private final int viewId;
        private int type;  //identify the type of the message: 0 normal message, 1 stable message, 2 message during flush algorithm
        private final String stablemessageId;   // identify the stable messageID: initialized to -1 if it is a normal message


        public ChatMsg(String id, int senderId, int view, int type, String stable) {
            this.id = id;
            this.senderId = senderId;
            this.viewId = view;
            this.type = type;
            this.stablemessageId = stable;
        }
    }

    // A message requesting to print the chat history
    public static class PrintHistoryMsg implements Serializable {
    }

    /* -- Actor constructor --------------------------------------------------- */
    public Chatter(int id) {
        this.id = id;
    }

    static public Props props(int id) {
        return Props.create(Chatter.class, () -> new Chatter(id));
    }

    public static class TimerMsg implements Serializable {
        public TimerMsg() {
        }
    }

    public static class FlushMsg implements Serializable {
        private final int viewId;
        private final int senderId;

        public FlushMsg(int viewId, int senderId) {
            this.viewId = viewId;
            this.senderId = senderId;
        }
    }

    public static class Beacon implements Serializable{}


    /* -- Actor behaviour ----------------------------------------------------- */
    private void sendChatMsg(String id, int type, String stable) {
        if (type == 0) {
            getContext().system().scheduler().scheduleOnce(Duration.create(3, TimeUnit.SECONDS), getSelf(), new TimerMsg(),
                    getContext().system().dispatcher(), null);
            if (inhibit_sends == 0)
                sendCount++;
        }
        if (inhibit_sends == 0) {
            ChatMsg m = new ChatMsg(id, this.id, this.viewId, type, stable);
            int index = findIndexViewId(this.viewId);
            boolean result = multicast(m, groups.get(index));
            if (result && type == 0) {
                stableMsg(id);
                appendToHistory(m); // append the sent message
            } else {

            }
        }
    }

    private void stableMsg(String id) {
        sendChatMsg(String.valueOf(this.id) + "- STABLE", 1, id);

    }

    private boolean multicast(Serializable m, Groups groups) { // our multicast implementation
        int message_sent = 0;
        List<ActorRef> shuffledGroup = new ArrayList<>(groups.group);
        Collections.shuffle(shuffledGroup);
        for (ActorRef p : shuffledGroup) {
            if (!p.equals(getSelf())) { // not sending to self
                p.tell(m, getSelf());
                message_sent++;
                try {
                    Thread.sleep(rnd.nextInt(10));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        if (message_sent == groups.group.size() - 1)
            return true;
        return false;
    }

    // Here we define the mapping between the received message types and our actor methods
    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(TimerMsg.class, this::onTimerMsg)
                .match(RequestJoin.class, this::onRequestJoin)
                .match(JoinGroupMsg.class, this::onJoinGroupMsg)
                .match(StartChatMsg.class, this::onStartChatMsg)
                .match(ChatMsg.class, this::onChatMsg)
                .match(PrintHistoryMsg.class, this::printHistory)
                .match(ViewMessage.class, this::onViewMessage)
                .match(FlushMsg.class, this::onFlush)
                .match(Timeout.class, this::onTimeout)
                .match(Beacon.class, this::onBeacon)
                .build();
    }

    private void onBeacon(Beacon beaconMessage) {

    }

    private void onTimerMsg(TimerMsg timerMsg) {
        sendChatMsg(String.valueOf(this.id) + "-" + String.valueOf(sendCount), 0, "-1");
    }

    private synchronized void onRequestJoin(RequestJoin rj) throws InterruptedException {   // manager receives the request to join a node to the group
        lastViewToBeInstalled++;

        int newId = Collections.max(groups.get(groups.size() - 1).listId) + 1;

        List<Integer> tmp = new ArrayList<>(groups.get(groups.size() - 1).listId);
        List<ActorRef> tmp1 = new ArrayList<>(groups.get(groups.size() - 1).group);

        tmp.add(newId);
        tmp1.add(getSender());

        this.groups.add(new Groups(lastViewToBeInstalled, tmp, tmp1));

        //System.out.println("Io sono: " + this.id + ", sono in onRequestJoin e i miei gruppi sono: ");
        displayGroup();

        getSender().tell(new JoinGroupMsg(newId, groups.get(groups.size() - 1)), getSelf()); //the manager informs the new node with the list of actors and his new ID

        //System.out.println("Io sono: " + this.id + ", sono in onRequestJoin, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId);

        CausalMulticast.addToGroup(getSender());

        viewChange();
        if ((groups.get(groups.size() - 1).group).size() == 2) {
            sendChatMsg(String.valueOf(this.id) + "-" + String.valueOf(sendCount), 0, "-1");
        }
    }

    private void onStartChatMsg(StartChatMsg msg) {
        sendChatMsg(msg.messageString, 0, "-1");
        setTimeout(BEACON_INTERVAL);
    }

    private void onJoinGroupMsg(JoinGroupMsg msg) {
        if (msg.id == 0) {
            this.groups.add(msg.groups);
        }
        this.id = msg.id;
        System.out.printf("%s: joining a group of %d peers with ID %02d\n",
                getSelf().path().name(), msg.groups.group.size(), this.id);
    }

    private void viewChange() {    // the manager sends the viewChange message to everyone in the group and updates itself view
        ViewMessage msg = new ViewMessage(this.groups.get(groups.size() - 1));
        //System.out.println("Io sono: " + this.id + ", sono in view change, sono nella vista: " + this.viewId + " e la mia listID è: " + this.groups.get(findIndexViewId(this.viewId)).listId);
        inhibit_sends++;
        multicast(msg, groups.get(groups.size() - 1));
        flush(lastViewToBeInstalled);
    }

    private void onViewMessage(ViewMessage vm) {   // participants receive a message with to change the view and they update
        // the view, the group and the list of IDs in the network
        inhibit_sends++;
        this.groups.add(vm.groups);

        //System.out.println("Io sono: " + this.id + ", sono in onview Message, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId);

        //TODO SEND ALL UNSTABLE MESSAGES TO EVERY NODE IN THE MOST RECENT VIEW

        flush(vm.groups.viewId);


        //System.out.println("listId size: " + groups.get(groups.size() - 1).listId.size());
        if (groups.get(groups.size() - 1).listId.get(groups.get(groups.size() - 1).listId.size() - 1) == this.id)
            getSelf().tell(new StartChatMsg(String.valueOf(this.id) + "-" + String.valueOf(sendCount)), getSelf());
    }

    private void flush(int viewId) {

        //System.out.println("Io sono: " + this.id + ", sono in flush, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);

        // TODO: CHECK WHICH NODE HAS TO RECEIVE THE MESSAGES (MULTICAST)
        Iterator<ActorRef> iterator = groups.get(groups.size() - 1).group.iterator();
        Iterator<ChatMsg> I = mq.iterator();
        while (I.hasNext()) {
            ChatMsg m = I.next();
            while (iterator.hasNext()) {  // send each message in the queue to all the nodes in the most recent view
                ActorRef a = iterator.next();
                if (!a.equals(getSelf())) {
                    m.type = 2;
                    a.tell(m, getSelf());
                    try {
                        Thread.sleep(rnd.nextInt(20));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    I.remove();
                }
            }
        }

        iterator = groups.get(groups.size() - 1).group.iterator(); // send a flush message to every actor in the most recent view
        while (iterator.hasNext()) {
            ActorRef a = iterator.next();
            if (!a.equals(getSelf())) {
                a.tell(new FlushMsg(viewId, this.id), getSelf());
            }
            try {
                Thread.sleep(rnd.nextInt(20));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void onFlush(FlushMsg flushMsg) {

        int index1 = findIndexViewId(this.viewId);
        System.out.println("Io sono: " + this.id + ", sono in onflush, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e il flush message viewid è: " + flushMsg.viewId + " e index = " + index1 + ", sto ricevendo flush da " + getSender());


        /*Iterator<Groups> I = this.groups.iterator();
        if(this.id == 2) {
            System.out.printf("\n------------------ \n");
            System.out.println("Actor id:" + this.id + " gropus size = " + groups.size());
            while (I.hasNext()) {
                Groups m = I.next();
                System.out.printf("view id: %d,", m.viewId);
                System.out.printf("ListId:  ");
                for (int i = 0; i < m.listId.size(); i++) {
                    System.out.printf("%d, ", m.listId.get(i));
                }
                System.out.printf("\n");
            }
            System.out.printf("------------------\n");
        }
*/

        intersectionListId = new ArrayList<>(groups.get(index1 + 1).group);
        receivedFlush.add(getSender());


        //System.out.println("Io sono: " + this.id + ", IntersectionList prima di remove: " + Arrays.toString(tmp.toArray()));
        if (!receivedFlush.contains(getSelf()))
            receivedFlush.add(getSelf());

        /*if (groups.size() - index1 > 1) {
            for (int i = 1; i < inhibit_sends; i++) {
                receivedFlush.retainAll(groups.get(index1 + i).group);
            }
        }*/

        if (receivedFlush.containsAll(intersectionListId)) { // If I received the flush messages from all the actors I need
            this.viewId = groups.get(index1 + 1).viewId;
            if (index1 != -1) {
                groups.remove(index1);  // remove the previous view in order to free memory
            }
            appendToHistory(flushMsg);
            inhibit_sends--;
            deleteOldMsg();
            /*if(this.id == 2)
                System.out.println("Io sono: " + this.id + ", sono in onFlush (dentro l'if), il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
            */
            receivedFlush.clear();
        }
    }

    private void onTimeout(Timeout timeoutMessage) {
        ActorRef manager = this.groups.get(findIndexViewId(this.viewId)).group.get(0);
        manager.tell(new Beacon(), getSelf());
        setTimeout(BEACON_INTERVAL);
    }

    private void setTimeout(int time) {
        getContext().system().scheduler().scheduleOnce(
                Duration.create(time, TimeUnit.MILLISECONDS),
                getSelf(),
                new Timeout(),
                getContext().system().dispatcher(), getSelf());
    }

    private int findIndexViewId(int viewId) {

        //System.out.println("Io sono: " + this.id + ", cerco vista: " + viewId + " la size del mio groups: " + groups.size());
        displayGroup();
        Iterator<Groups> I = groups.iterator();
        int counter = 0;
        while (I.hasNext()) {
            Groups m = I.next();
            if (m.viewId == viewId) {
                /*if(this.id == 2)
                    System.out.println("Io sono: " + this.id + ", vista trovata in posizione: " + counter);*/
                return counter;
            }
            counter++;
        }
        return -1;
    }

    private void deleteOldMsg() {
        Iterator<ChatMsg> I = mq.iterator();
        while (I.hasNext()) {
            ChatMsg m = I.next();
            if (m.viewId < this.viewId)
                I.remove();
        }
    }

    private void onChatMsg(ChatMsg msg) {

        if (msg.type == 1) {  // stable message
            final ChatMsg deliverable = findDeliverable(msg);
            deliver(deliverable);
        } else if (msg.type == 0) {  // normal message
            if ((msg.viewId > this.viewId) || (msg.viewId == this.viewId && !findDuplicate(msg))) {
                this.mq.add(msg); // cannot deliver m right now, putting it on hold
                System.out.printf("%02d: enqueue from %02d.... queue length: %d\n", this.id, msg.senderId, mq.size());
            }
        } else if (msg.type == 2) {
            if (msg.viewId == this.viewId && !findDuplicate(msg)) {
                deliver(msg);
            } else if (msg.viewId > this.viewId)
                this.mq.add(msg);
        }
    }

    private boolean findDuplicate(ChatMsg chatMsg) {
        Iterator<ChatMsg> I = delivered.iterator();
        while (I.hasNext()) {
            ChatMsg m = I.next();
            if (m.id.equals(chatMsg.id))
                return true;
        }
        return false;
    }

    // find a message in the queue that can be delivered now
    // if found, remove it from the queue and return it
    private ChatMsg findDeliverable(ChatMsg stableMessage) {
        Iterator<ChatMsg> I = mq.iterator();
        while (I.hasNext()) {
            ChatMsg m = I.next();
            if (canDeliver(stableMessage, m)) {
                I.remove();
                return m;
            }
        }
        return null;        // nothing can be delivered right now
    }

    private boolean canDeliver(ChatMsg stableMessage, ChatMsg incoming) {
        return (stableMessage.stablemessageId.equals(incoming.id));
    }

    private void deliver(ChatMsg m) {
        appendToHistoryDeliver(m);
        delivered.add(m);
    }

    private void appendToHistory(ChatMsg m) {
        chatHistory.append("send multicast " + m.id + " within " + this.viewId + " " + "\n");
    }

    private void appendToHistoryDeliver(ChatMsg chatMsg) {
        chatHistory.append("deliver multicast " + chatMsg.id + " from " + chatMsg.senderId + " within " + this.viewId + "\n");
    }

    private void appendToHistory(FlushMsg m) {
        //System.out.println("Io sono: " + this.id + ", sono in appendtohistory, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
        chatHistory.append("install view " + m.viewId + " " + display() + "\n");
    }

    private void printHistory(PrintHistoryMsg msg) {
        System.out.printf("%02d: %s\n", this.id, chatHistory);
    }

    private String display() {
        String s = "";
        for (int i = 0; i < this.listId.size(); i++) {
            s += this.listId.get(i).toString();
            if (i < this.listId.size() - 1)
                s += ", ";
        }
        return s;
    }

    private void displayGroup() {
        /*
            Iterator<Groups> I = this.groups.iterator();
            while (I.hasNext()) {
                Groups m = I.next();
                System.out.printf("\n------------------ \n");
                System.out.println("Actor id:"+ this.id);
                System.out.printf("view id: %d,", m.viewId);
                System.out.printf("ListId:  ");
                for (int i = 0; i < m.listId.size(); i++) {
                    System.out.printf("%d, ", m.listId.get(i));
                }
                System.out.printf("\n");
                System.out.printf("------------------\n");
            }
        */
    }

}
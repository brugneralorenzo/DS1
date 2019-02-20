package it.unitn.ds1;
import akka.actor.ActorRef;
import akka.actor.AbstractActor;

import java.util.*;
import java.io.Serializable;
import akka.actor.Props;

import java.lang.Thread;
import java.lang.InterruptedException;
import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;


class Chatter extends AbstractActor {
  
  private Random rnd = new Random();
  private List<ActorRef> group; // the list of peers (the multicast group)
  private int sendCount = 0;    // number of sent messages
  private int id;    // ID of the current actor
  private int viewId = 0;    //the ID of the view
  private List<Integer> listId = new ArrayList<>();
  private int inhibit_sends = 0;
  private int flushCount = 0;
  private List<ChatMsg> delivered = new ArrayList<>();
  private int lastViewToBeInstalled = 0;
  private List<Groups> groups = new ArrayList<>();




  // a buffer storing all received chat messages
  private StringBuffer chatHistory = new StringBuffer();
  // message queue to hold out-of-order messages
  private List<ChatMsg> mq = new ArrayList<>();

  /* -- Message types ------------------------------------------------------- */
  
  // Start message that informs every chat participant about its peers
  public static class JoinGroupMsg implements Serializable {
    private final List<ActorRef> group; // list of group members
    private final int id;
      public JoinGroupMsg(List<ActorRef> group, int id) {
      this.group = group;
      this.id = id;
    }
  }

  public static class Groups {
    public int viewId;
    public List<Integer> listId = new ArrayList<>();
    public List<ActorRef> group = new ArrayList<>();
    public Groups(int viewId, List<Integer> listId, List<ActorRef> group){
      this.viewId = viewId;
      this.listId = listId;
      this.group = group;
    }
  }

  // A message requesting the peer to start a discussion on his topic
  public static class StartChatMsg implements Serializable {}

  public static class RequestJoin implements Serializable {}

  public static class ViewMessage implements Serializable {
      private int viewId;
      private List<ActorRef> group;
      private List<Integer> listId;
      public ViewMessage(int id, List<ActorRef> group, List<Integer> listId){
          this.viewId = id;
          this.group = group;
          this.listId = listId;
      }
  }


    // Chat message
  public static class ChatMsg implements Serializable {
    public final String id;      // the ID of the message composed by message ID and sender ID
    public final int senderId;   // the ID of the message sender
    public final int viewId;
    private int type;  //identify the type of the message: 0 normal message, 1 stable message, 2 message during flush algorithm
    public final String stablemessageId;   // identify the stable messageID: initialized to -1 if it is a normal message


    public ChatMsg(String id, int senderId, int view, int type, String stable) {
      this.id = id;
      this.senderId = senderId;
      this.viewId = view;
      this.type = type;
      this.stablemessageId = stable;
    }
  }

  // A message requesting to print the chat history
  public static class PrintHistoryMsg implements Serializable {}

  /* -- Actor constructor --------------------------------------------------- */
  public Chatter(int id) {
    this.id = id;
  }

  static public Props props(int id) {
    return Props.create(Chatter.class, () -> new Chatter(id));
  }

  public static class TimerMsg implements Serializable {
    public TimerMsg(){}
  }

  public static class FlushMsg implements Serializable {
    private int viewId;
    public FlushMsg(int viewId){
      this.viewId = viewId;
    }
  }


  /* -- Actor behaviour ----------------------------------------------------- */
  private void sendChatMsg(String id, int type, String stable) {
    if (type == 0) {
      getContext().system().scheduler().scheduleOnce(Duration.create(3, TimeUnit.SECONDS), getSelf(), new TimerMsg(),
              getContext().system().dispatcher(), null);
      if (inhibit_sends == 0)
         sendCount++;
    }
    if (inhibit_sends == 0){
      ChatMsg m = new ChatMsg(id, this.id, this.viewId, type, stable);
      boolean result = multicast(m);
      if(result && type == 0) {
        stableMsg(id);
        appendToHistory(m); // append the sent message
      }
    }
  }

  private void stableMsg(String id){
      sendChatMsg(String.valueOf(this.id) + "- STABLE", 1, id);

  }

  private boolean multicast(Serializable m) { // our multicast implementation
    List<ActorRef> shuffledGroup = new ArrayList<>(group);
    Collections.shuffle(shuffledGroup);
    for (ActorRef p: shuffledGroup) {
      if (!p.equals(getSelf())) { // not sending to self
        p.tell(m, getSelf());
        try { Thread.sleep(rnd.nextInt(10)); } 
        catch (InterruptedException e) { e.printStackTrace(); }
      }
    }
    return true;
  }

  // Here we define the mapping between the received message types
  // and our actor methods
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(TimerMsg.class,        this::onTimerMsg)
      .match(RequestJoin.class,     this::onRequestJoin)
      .match(JoinGroupMsg.class,    this::onJoinGroupMsg)
      .match(StartChatMsg.class,    this::onStartChatMsg)
      .match(ChatMsg.class,         this::onChatMsg)
      .match(PrintHistoryMsg.class, this::printHistory)
      .match(ViewMessage.class,     this::onViewMessage)
      .match(FlushMsg.class,        this::onFlush)
      .build();
  }

    private void onTimerMsg(TimerMsg timerMsg){
      sendChatMsg(String.valueOf(this.id) + "-"+ String.valueOf(sendCount), 0, "-1");
    }

    private synchronized void onRequestJoin(RequestJoin rj){   // manager receives the request to join a node to the group
      lastViewToBeInstalled++;
      if(listId.size() == 0) {
        listId.add(0);
        groups.add(new Groups(0, listId, group));
      }


      int newId = Collections.max(groups.get(groups.size() -1).listId) +1;
      List<Integer> tmp = groups.get(groups.size() -1).listId;
      tmp.add(newId);
      List<ActorRef> tmp1 = groups.get(groups.size() -1).group;
      tmp1.add(getSender());


      groups.add(new Groups(lastViewToBeInstalled, tmp, tmp1));

      getSender().tell(new JoinGroupMsg(tmp1, newId), getSelf());

      System.out.println("Io sono: " + this.id + ", sono in onRequestJoin, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId);

      viewChange();
      if (group.size() == 2) {
        sendChatMsg(String.valueOf(this.id) + "-"+ String.valueOf(sendCount), 0, "-1");
      }
    }

    private void onStartChatMsg(StartChatMsg msg) {
        //sendChatMsg(0, 0, -1); // start with message 0
    }

    private void onJoinGroupMsg(JoinGroupMsg msg) {
        this.group = msg.group;
        this.id = msg.id;
        System.out.printf("%s: joining a group of %d peers with ID %02d\n",
                getSelf().path().name(), this.group.size(), this.id);
    }

    private void viewChange(){    // the manager sends the viewChange message to everyone in the group and updates itself view
      //this.viewId ;
      ViewMessage msg = new ViewMessage(lastViewToBeInstalled, this.group, listId);
      inhibit_sends ++;
      multicast(msg);
      System.out.println("Io sono: " + this.id + ", sono in view change, sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
      flush(lastViewToBeInstalled);

    }

    private void onViewMessage(ViewMessage vm){   // participants receive a message with to change the view and they update
                                                  // the view, the group and the list of IDs in the network
      inhibit_sends ++;
      this.group = vm.group;
      this.listId = vm.listId;
      System.out.println("Io sono: " + this.id + ", sono in onview Message, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
      flush(vm.viewId);
      if ((listId.get(listId.size() -1)) == this.id)
          sendChatMsg(String.valueOf(this.id) + "-" + String.valueOf(sendCount), 0, "-1");
    }

    private void flush(int viewId){
      System.out.println("Io sono: " + this.id + ", sono in flush, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
      Iterator<ChatMsg> I = mq.iterator();
      while (I.hasNext()) {
        ChatMsg m = I.next();
        m.type = 2;
        multicast(m);
        I.remove();
      }
      multicast(new FlushMsg(viewId));
    }

    private void onFlush(FlushMsg flushMsg){
      flushCount ++;

      int index = findIndexViewId(flushMsg.viewId);
      System.out.println("Io sono: " + this.id + ", sono in ONFLUSH, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e il flush message viewid è: " + flushMsg.viewId + " e index = "+ index);

      stampaGroup();
      System.out.printf("flush msg view Id =  \n",flushMsg.viewId);
      if (flushCount == groups.get(index).listId.size()-1) {
        this.viewId = flushMsg.viewId;
        this.group = groups.get(index).group;
        this.listId = groups.get(index).listId;
        appendToHistory(flushMsg);
        deleteOldMsg();
        inhibit_sends --;
        System.out.println("Io sono: " + this.id + ", sono in onFlush (dentro l'if), il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
      }
    }

    private int findIndexViewId (int viewId){
    System.out.printf("vista alla find: "+ viewId + "la size di groups: "+ groups.size() + "\n");
      Iterator<Groups> I = groups.iterator();
      int counter = 0;
      while (I.hasNext()) {
        counter++;
        Groups m = I.next();
        System.out.printf("vista trovata nella strutt dati =: "+ m.viewId);
        if (m.viewId == viewId)
          return counter;
      }
      return -1;
    }


    private void deleteOldMsg(){
      Iterator<ChatMsg> I = mq.iterator();
      while (I.hasNext()) {
        ChatMsg m = I.next();
        if (m.viewId < this.viewId)
          I.remove();
      }
    }

  private void onChatMsg(ChatMsg msg) {
    if (msg.type == 1) {
      final ChatMsg deliverable = findDeliverable(msg);
      deliver(deliverable);
    }
    else if (msg.type == 2){
        if (msg.viewId > this.viewId)
          this.mq.add(msg);
        else if (msg.viewId == this.viewId && !findDuplicate(msg))
          deliver(msg);
    }
    else {
      this.mq.add(msg);   // cannot deliver m right now, putting it on hold
      System.out.printf("%02d: enqueue from %02d.... queue length: %d\n", this.id, msg.senderId, mq.size());
    }
  }

  private boolean findDuplicate(ChatMsg chatMsg){
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

  private void appendToHistoryDeliver(ChatMsg chatMsg){
    chatHistory.append("deliver multicast " + chatMsg.id + " from " + chatMsg.senderId + " within " + this.viewId + "\n");
  }

  private void appendToHistory(FlushMsg m) {
    System.out.println("Io sono: " + this.id + ", sono in appendtohistory, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
    chatHistory.append("install view " + m.viewId + " "+ display() + "\n");
  }

  private void printHistory(PrintHistoryMsg msg) {
    System.out.printf("%02d: %s\n", this.id, chatHistory);
  }

  private String display(){
      String s = "";
    for(int i = 0; i < this.listId.size(); i++) {
        s += this.listId.get(i).toString();
        if (i < this.listId.size() -1)
            s += ", ";
    }
    return s;
  }

  private void stampaGroup(){
    Iterator<Groups> I = groups.iterator();
    while (I.hasNext()) {
      Groups m = I.next();
      System.out.printf("\n------------------ \n" );
      System.out.printf("view id: %d,", m.viewId);
      System.out.printf("ListId:  " );
      for (int i = 0; i < m.listId.size(); i++) {
        System.out.printf("%d, ", m.listId.get(i));
      }
      System.out.printf("\n" );
      System.out.printf("------------------\n" );
    }
  }
}
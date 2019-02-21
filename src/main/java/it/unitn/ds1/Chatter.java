  package it.unitn.ds1;
  import akka.actor.ActorRef;
  import akka.actor.AbstractActor;

  import java.util.*;
  import java.io.Serializable;
  import akka.actor.Props;

  import java.lang.Thread;
  import java.lang.InterruptedException;
  import java.util.concurrent.TimeUnit;

  import jdk.internal.cmm.SystemResourcePressureImpl;
  import scala.concurrent.duration.Duration;


  class Chatter extends AbstractActor {

    private Random rnd = new Random();
    private int sendCount = 0;    // number of sent messages
    private int id;    // ID of the current actor
    private int viewId = 0;    //the ID of the view
    private List<Integer> listId = new ArrayList<>();
    private int inhibit_sends = 0;
    private List<ChatMsg> delivered = new ArrayList<>();
    private int lastViewToBeInstalled = 0;
    private List<Groups> groups = new ArrayList<>();
    private List<ActorRef> intersectionListId = new ArrayList<>();




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
     private Groups groups;
        public ViewMessage(Groups groups){
            this.groups = groups;
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
      private int senderId;
      public FlushMsg(int viewId, int senderId){
        this.viewId = viewId;
        this.senderId = senderId;
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
      List<ActorRef> shuffledGroup = new ArrayList<>(groups.get(groups.size() -1).group);
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

    // Here we define the mapping between the received message types and our actor methods
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

        int newId = Collections.max(groups.get(groups.size() -1).listId) +1;
        List<Integer> tmp = new ArrayList<>(groups.get(groups.size() -1).listId);
        List<ActorRef> tmp1 = new ArrayList<>(groups.get(groups.size() -1).group);
        tmp.add(newId);
        tmp1.add(getSender());
        this.groups.add(new Groups(lastViewToBeInstalled, tmp, tmp1));
        System.out.println("Io sono: " + this.id + ", sono in onRequestJoin e i miei gruppi sono: ");
        displayGroup();

        getSender().tell(new JoinGroupMsg(newId, groups.get(groups.size() -1)), getSelf()); //the manager informs the new node with the list of actors and his new ID

        System.out.println("Io sono: " + this.id + ", sono in onRequestJoin, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId);

        viewChange();
        if ((groups.get(groups.size() -1).group).size() == 2) {
          sendChatMsg(String.valueOf(this.id) + "-"+ String.valueOf(sendCount), 0, "-1");
        }
      }

      private void onStartChatMsg(StartChatMsg msg) {
          //sendChatMsg(0, 0, -1); // start with message 0
      }

      private void onJoinGroupMsg(JoinGroupMsg msg) {
        if (msg.id == 0) {
            this.groups.add(msg.groups);
        }
        this.id = msg.id;
        System.out.printf("%s: joining a group of %d peers with ID %02d\n",
                  getSelf().path().name(), msg.groups.group.size(), this.id);
      }

      private void viewChange(){    // the manager sends the viewChange message to everyone in the group and updates itself view
        ViewMessage msg = new ViewMessage(this.groups.get(groups.size() -1));
        System.out.println("Io sono: " + this.id + ", sono in view change, sono nella vista: " + this.viewId + " e la mia listID è: " + this.groups.get(findIndexViewId(this.viewId)).listId);
        inhibit_sends++;
        multicast(msg);
        flush(lastViewToBeInstalled);
      }

      private void onViewMessage(ViewMessage vm){   // participants receive a message with to change the view and they update
                                                    // the view, the group and the list of IDs in the network
        inhibit_sends ++;
        this.groups.add(vm.groups);
        System.out.println("Io sono: " + this.id + ", sono in onview Message, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId);

        //TODO SEND ALL UNSTABLE MESSAGES TO EVERY NODE IN THE MOST RECENT VIEW

        flush(vm.groups.viewId);

        //TODO CHECK THESE TWO LINES OF CODE

        if ((groups.get(groups.size() -1).listId.size()-1) == this.id)
            sendChatMsg(String.valueOf(this.id) + "-" + String.valueOf(sendCount), 0, "-1");
      }

      private void flush(int viewId){
        System.out.println("Io sono: " + this.id + ", sono in flush, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);

        // TODO: CHECK WHICH NODE HAS TO RECEIVE THE MESSAGES (MULTICAST)
        Iterator<ChatMsg> I = mq.iterator();
        while (I.hasNext()) {
          ChatMsg m = I.next();
          m.type = 2;
          multicast(m);
          I.remove();
        }

        Iterator<ActorRef> iterator = groups.get(groups.size() -1).group.iterator(); // send a flush message to every actor in the most recent view
          while (iterator.hasNext()){
              ActorRef a = iterator.next();
              if (!a.equals(getSelf())) {
                  a.tell(new FlushMsg(viewId, this.id), getSelf());
              }
          }
      }

      private void onFlush(FlushMsg flushMsg){

          System.out.println("Io sono: " + this.id + " ed entro in onFlush");
        int index1 = findIndexViewId(this.viewId);
        System.out.println("Io sono: " + this.id + ", sono in ONFLUSH, il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e il flush message viewid è: " + flushMsg.viewId + " e index = "+ index1);

        if (this.id == 0)
            displayGroup();
            intersectionListId = groups.get(index1 + 1).group;
            List<ActorRef> tmp = new ArrayList<>(intersectionListId);

            System.out.println("Io sono: " + this.id + ", IntersectionList prima di remove: " + Arrays.toString(tmp.toArray()));
            tmp.remove(getSelf());
            System.out.println("Io sono: " + this.id + ", IntersectionList DOPO di remove: " + Arrays.toString(tmp.toArray()));

            if (groups.size() - index1 > 1) {
                for (int i = 1; i < inhibit_sends; i++) {
                    System.out.println("Io sono: " + this.id + " e sono dentro al FOR");
                    tmp.retainAll(groups.get(index1 + i).group);
                }
            }
        System.out.println("Io sono: " + this.id + ", IntersectionList: " + Arrays.toString(tmp.toArray()));
            System.out.println("Io sono: " + this.id + " e il sender del messaggio flush è: " + flushMsg.senderId);

        //tmp.remove(flushMsg.senderId); // remove the ID of the sender from the intersection list

          tmp.remove(getSender());

        if (tmp.isEmpty()) { // If I received the flush messages from all the actors I need
          this.viewId = groups.get(index1 +1).viewId;
          if (index1 != -1 && this.id == 0) {
              groups.remove(index1);  // remove the previous view in order to free memory
          }
          appendToHistory(flushMsg);
          inhibit_sends --;
          deleteOldMsg();
          System.out.println("Io sono: " + this.id + ", sono in onFlush (dentro l'if), il mio inhibit_sends è: " + inhibit_sends + ", sono nella vista: " + this.viewId + " e la mia listID è: " + this.listId);
        }
      }

      private int findIndexViewId (int viewId){
      System.out.println("Io sono: " + this.id  + ", vista alla find: "+ viewId + " la size di groups: "+ groups.size());
      displayGroup();
        Iterator<Groups> I = groups.iterator();
        int counter = 0;
        while (I.hasNext()) {
          Groups m = I.next();
          if (m.viewId == viewId) {
              System.out.println("Io sono: " + this.id  +", vista trovata in posizione: "+ counter);
              return counter;
          }
          counter++;
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

      //TODO GESTIRE MESSAGGI DI TIPO 2 CHE SONO QUELLI CHE SONO STATI RICEVUTI MA NON STABILI

      if (msg.type == 1) {  // stable message
        final ChatMsg deliverable = findDeliverable(msg);
        deliver(deliverable);
      }
      else if (msg.type == 0) {  // normal message
        if ((msg.viewId > this.viewId) || (msg.viewId == this.viewId && !findDuplicate(msg))) {
          this.mq.add(msg); // cannot deliver m right now, putting it on hold
          System.out.printf("%02d: enqueue from %02d.... queue length: %d\n", this.id, msg.senderId, mq.size());
        }
      }
      else {
        if (msg.viewId == this.viewId && !findDuplicate(msg)) {
          deliver(msg);
        }
        else if (msg.viewId > this.viewId)
          this.mq.add(msg);
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

    private void displayGroup(){
      Iterator<Groups> I = this.groups.iterator();
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
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Thirty independent logical clients, each with a sender and a receiver. */
public final class BookingClients {
    record Pending(int id,String op,int[] seats,long sentNs,int delayMs) {}
    record FinalState(int responses,int notifications,int unresolved,int[] held,long responseNs) {}
    static final class ClientState {
        final int id;final Pending[] requests;final BitSet responded=new BitSet(),waitlisted=new BitSet(),notified=new BitSet();
        final long[] versions=new long[Shared.SEATS+1];final int[] owners=new int[Shared.SEATS+1];
        final TreeMap<Integer,Long> held=new TreeMap<>();final Map<Integer,Integer> cancelling=new HashMap<>();long responseNs;
        ClientState(int id,int count){this.id=id;requests=new Pending[count+1];}
        synchronized void register(Pending p){if(requests[p.id()]!=null)throw new IllegalStateException("Duplicate local request");requests[p.id()]=p;if(p.op().equals("CANCEL"))cancelling.put(p.seats()[0],p.id());}
        synchronized List<Integer> cancellable(){return held.keySet().stream().filter(n->!cancelling.containsKey(n)).toList();}
        void apply(Shared.State state,boolean grant) {
            int seat=state.seat();long v=state.version();if(seat<1||seat>100||v<0||state.owner()<0||state.owner()>30)throw new IllegalArgumentException("Bad state");
            if(v<versions[seat])return;
            if(v==versions[seat]&&owners[seat]!=state.owner())throw new IllegalStateException("Same version has conflicting owner");
            versions[seat]=v;owners[seat]=state.owner();
            if(state.owner()!=id)held.remove(seat);else if(grant)held.put(seat,v);
        }
        synchronized Pending response(int req,String result,List<Shared.State> states,long now) {
            Pending p=request(req);if(responded.get(req))throw new IllegalStateException("Duplicate RESP");
            if(!Set.of("SUCCESS","FAIL","WAITLISTED").contains(result))throw new IllegalArgumentException("Bad result");
            if(result.equals("WAITLISTED")){if(!p.op().equals("RESERVE"))throw new IllegalStateException("MULTI/CANCEL waitlisted");waitlisted.set(req);}
            else if(notified.get(req))throw new IllegalStateException("NOTIFY without WAITLISTED");
            boolean grant=result.equals("SUCCESS")&&!p.op().equals("CANCEL");for(Shared.State state:states)apply(state,grant);
            if(p.op().equals("CANCEL"))cancelling.remove(p.seats()[0],req);
            responded.set(req);responseNs+=now-p.sentNs();return p;
        }
        synchronized Pending notification(int req,int seat,long version) {
            Pending p=request(req);if(!p.op().equals("RESERVE")||p.seats().length!=1||p.seats()[0]!=seat||version<=0)throw new IllegalStateException("Bad NOTIFY association");
            if(notified.get(req))throw new IllegalStateException("Duplicate NOTIFY");
            if(responded.get(req)&&!waitlisted.get(req))throw new IllegalStateException("NOTIFY for non-waitlist request");
            notified.set(req);apply(new Shared.State(seat,version,id),true);return p;
        }
        synchronized Pending request(int req){if(req<1||req>=requests.length||requests[req]==null)throw new IllegalArgumentException("Unknown req id");return requests[req];}
        synchronized FinalState snapshot(){BitSet unresolved=(BitSet)waitlisted.clone();unresolved.andNot(notified);return new FinalState(responded.cardinality(),notified.cardinality(),unresolved.cardinality(),held.keySet().stream().mapToInt(Integer::intValue).toArray(),responseNs);}
    }
    final Shared.Config cfg;final List<Client> clients=new ArrayList<>();final AtomicReference<String> failure=new AtomicReference<>();
    final long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(90);
    BookingClients(Shared.Config cfg){this.cfg=cfg;cfg.require("--host");}
    void fail(String reason) {
        if(failure.compareAndSet(null,Shared.clean(reason))) {
            System.err.println("CLIENTS FAIL: "+reason);
            for(Client c:clients){Shared.quietly(c.socket);if(c.sender!=null)c.sender.interrupt();if(c.receiver!=null)c.receiver.interrupt();}
        }
    }
    final class Client {
        final int id;final long seed;final Random random;final ClientState state;final Shared.Log log;
        final CountDownLatch connected=new CountDownLatch(1),sentDone=new CountDownLatch(1);
        volatile Socket socket;volatile int sent;volatile boolean done;Thread sender,receiver;
        Client(int id,String hash)throws Exception {
            this.id=id;seed=cfg.seed()^(0x9E3779B97F4A7C15L*id);random=new Random(seed);state=new ClientState(id,cfg.requests);
            log=new Shared.Log(cfg.logDir.resolve("Client"+id+".txt"),"CLIENT"+id);
            log.event("INIT","INFO",cfg.init(hash)+" client="+id+" seed="+seed+" host="+Shared.clean(cfg.require("--host")));
        }
        void start(){sender=new Thread(this::sendLoop,"g1hw2-client-"+id+"-sender");receiver=new Thread(this::receiveLoop,"g1hw2-client-"+id+"-receiver");receiver.start();sender.start();}
        int pickSeat(){return random.nextInt(100)<80?1+random.nextInt(10):1+random.nextInt(100);}
        Pending generate(int req,int delay,long now) {
            List<Integer> own=state.cancellable();int roll=random.nextInt(100);String op;int[] seats;
            if(!own.isEmpty()&&roll>=70){op="CANCEL";seats=new int[]{own.get(random.nextInt(own.size()))};}
            else {
                boolean multi=own.isEmpty()?roll>=70:roll>=50;op=multi?"RESERVE_MULTI":"RESERVE";
                if(!multi)seats=new int[]{pickSeat()};
                else {LinkedHashSet<Integer> chosen=new LinkedHashSet<>();int count=2+random.nextInt(3);while(chosen.size()<count)chosen.add(pickSeat());seats=chosen.stream().mapToInt(Integer::intValue).toArray();}
            }
            return new Pending(req,op,seats,now,delay);
        }
        void sendLoop() {
            try {
                long connectDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(true) {
                    if(failure.get()!=null)throw new IOException("Run already failed");
                    socket=new Socket();socket.setTcpNoDelay(true);
                    try {socket.connect(new InetSocketAddress(cfg.require("--host"),cfg.port),Math.max(1,(int)Math.min(10000,TimeUnit.NANOSECONDS.toMillis(connectDeadline-System.nanoTime()))));break;}
                    catch(ConnectException e){Shared.quietly(socket);if(System.nanoTime()>=connectDeadline)throw e;Thread.sleep(200);}
                }
                socket.setSoTimeout(1000);
                BufferedWriter output=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
                output.write("HELLO "+cfg.runId+" "+id+" "+cfg.profile+"\n");output.flush();
                log.event("CONNECT","SUCCESS","client="+id+" type=HELLO");connected.countDown();
                long lastSentNs=0;
                for(int req=1;req<=cfg.requests&&failure.get()==null;req++) {
                    int delay=cfg.minMs+random.nextInt(cfg.maxMs-cfg.minMs+1);if(delay>0)Thread.sleep(delay);
                    if(System.nanoTime()>deadline)throw new SocketTimeoutException("Client run timeout");
                    Pending p=generate(req,delay,System.nanoTime());state.register(p);
                    log.event("SEND","INFO","client="+id+" req="+req+" op="+p.op()+" seats="+Shared.csv(p.seats())+" delayMs="+delay+" sentNs="+p.sentNs()+" gapNs="+(lastSentNs==0?0:p.sentNs()-lastSentNs));
                    output.write(p.op()+" "+req+" "+Shared.csv(p.seats())+"\n");output.flush();sent++;lastSentNs=p.sentNs();
                }
            }catch(Exception e){if(failure.get()==null)fail("client "+id+" sender "+e.getClass().getSimpleName()+": "+e.getMessage());}
            finally {connected.countDown();sentDone.countDown();}
        }
        List<Shared.State> parseStates(String text) {
            if(text.equals("-"))return List.of();List<Shared.State> states=new ArrayList<>();Set<Integer> unique=new HashSet<>();
            for(String part:text.split(",",-1)){String[] p=part.split(":",-1);if(p.length!=3)throw new IllegalArgumentException("Bad seat state");int seat=Integer.parseInt(p[0]);if(!unique.add(seat))throw new IllegalArgumentException("Duplicate state");states.add(new Shared.State(seat,Long.parseLong(p[1]),Integer.parseInt(p[2])));}return states;
        }
        void message(String line)throws Exception {
            String[] p=line.split(" ",-1);long now=System.nanoTime();
            if(p[0].equals("RESP")&&p.length==5) {
                int req=Integer.parseInt(p[1]);Pending request=state.response(req,p[2],parseStates(p[4]),now);
                log.event("RESPONSE",p[2].equals("WAITLISTED")?"INFO":p[2],"client="+id+" req="+req+" op="+request.op()+" seats="+Shared.csv(request.seats())+" result="+p[2]+" reason="+p[3]+" states="+p[4]+" elapsedNs="+(now-request.sentNs()));
                log.event(request.op(),p[2].equals("WAITLISTED")?"INFO":p[2],"client="+id+" req="+req+" result="+p[2]);
            } else if(p[0].equals("NOTIFY")&&p.length==4) {
                int req=Integer.parseInt(p[1]),seat=Integer.parseInt(p[2]);long version=Long.parseLong(p[3]);state.notification(req,seat,version);
                log.event("NOTIFY","SUCCESS","client="+id+" req="+req+" seat="+seat+" version="+version);
            } else if(p[0].equals("BYE")&&p.length==3&&p[1].equals(cfg.runId)) {
                if(!sentDone.await(30,TimeUnit.SECONDS)||failure.get()!=null)throw new IOException("Sender did not finish");
                FinalState finalState=state.snapshot();int pending=Integer.parseInt(p[2]);
                if(sent!=cfg.requests||finalState.responses()!=cfg.requests||finalState.unresolved()!=pending)throw new IOException("BYE counts mismatch");
                log.event("PROTOCOL","SUCCESS","type=BYE client="+id+" unresolved="+pending);
                log.event("STAT","INFO","type=FINAL client="+id+" sent="+sent+" firstResponses="+finalState.responses()+" notify="+finalState.notifications()+" unresolved="+finalState.unresolved()+" held="+Shared.csv(finalState.held())+" responseNs="+finalState.responseNs());
                log.event("TERMINATE","SUCCESS","execution=COMPLETE client="+id);done=true;
            } else throw new IOException("Unexpected server frame: "+Shared.clean(line));
        }
        void receiveLoop() {
            try {
                connected.await();if(failure.get()!=null)return;
                if(socket==null||!socket.isConnected())throw new IOException("No socket");InputStream input=socket.getInputStream();Shared.Frames frames=new Shared.Frames();byte[] buffer=new byte[16384];
                while(!done&&failure.get()==null) {
                    if(System.nanoTime()>deadline)throw new SocketTimeoutException("Client run timeout");
                    int n;try{n=input.read(buffer);}catch(SocketTimeoutException e){continue;}
                    if(n<0)throw new EOFException("Server EOF before BYE");frames.feed(ByteBuffer.wrap(buffer,0,n),line->{if(done)throw new IOException("Frame after BYE");message(line);});
                    if(done&&frames.partial())throw new IOException("Partial frame after BYE");
                }
            }catch(Exception e){if(failure.get()==null)fail("client "+id+" receiver "+e.getClass().getSimpleName()+": "+e.getMessage());}
            finally {Shared.quietly(socket);}
        }
    }
    int run()throws Exception {
        try {
            String hash=Shared.artifactHash();for(int id=1;id<=cfg.clients;id++)clients.add(new Client(id,hash));for(Client c:clients)c.start();
            long lastPrint=0;
            while(true) {
                boolean alive=false;for(Client c:clients){c.sender.join(50);c.receiver.join(50);alive|=c.sender.isAlive()||c.receiver.isAlive();}
                long now=System.nanoTime();if(now-lastPrint>TimeUnit.SECONDS.toNanos(5)){lastPrint=now;int sent=0,resp=0;for(Client c:clients){sent+=c.sent;resp+=c.state.snapshot().responses();}System.out.println("CLIENTS sent="+sent+" firstResponses="+resp+" run="+cfg.runId);}
                if(!alive)break;if(now>deadline)fail("Overall client timeout");
            }
            if(clients.stream().anyMatch(c->!c.done))fail("Missing successful client termination");
        }catch(Exception e){fail(e.getClass().getSimpleName()+": "+e.getMessage());}
        finally {
            for(Client c:clients){Shared.quietly(c.socket);if(failure.get()!=null)c.log.event("TERMINATE","FAIL","reason="+failure.get());c.log.close();}
        }
        return failure.get()==null?0:1;
    }
    public static void main(String[] args) {
        int code;try{code=new BookingClients(new Shared.Config(args)).run();}
        catch(IllegalArgumentException e){System.err.println(e.getMessage());code=2;}
        catch(Exception e){e.printStackTrace();code=1;}if(code!=0)System.exit(code);
    }
}

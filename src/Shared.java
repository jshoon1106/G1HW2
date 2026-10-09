import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/** Shared data and synchronous utilities. Creates no threads. */
final class Shared {
    static final int SEATS = 100, WORKERS = 10, MAX_FRAME = 16384;
    static final long SEND_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(30);
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final class Config {
        final Map<String,String> args = new LinkedHashMap<>();
        final String profile, runId;
        final int clients, requests, minMs, maxMs, port;
        final Path logDir;
        Config(String[] argv) {
            for (int i=0;i<argv.length;i+=2) {
                if (i+1>=argv.length || !argv[i].startsWith("--") || args.put(argv[i],argv[i+1])!=null)
                    throw new IllegalArgumentException("Expected unique --option value pairs");
            }
            Set<String> allowed=Set.of("--profile","--run-id","--log-dir","--clients","--requests","--min-ms","--max-ms","--port","--bind","--host","--seed");
            for(String key:args.keySet()) if(!allowed.contains(key)) throw new IllegalArgumentException("Unknown option "+key);
            profile=args.getOrDefault("--profile","final");
            if(!Set.of("final","dev").contains(profile)) throw new IllegalArgumentException("Bad profile");
            runId=require("--run-id");
            if(!runId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Bad run id");
            if(runId.equals("auto")&&args.containsKey("--host")) throw new IllegalArgumentException("auto is reserved for standby servers; use a fresh client run id");
            clients=number("--clients",30,1,30); requests=number("--requests",5000,1,5000);
            minMs=number("--min-ms",200,0,1000); maxMs=number("--max-ms",1000,minMs,1000);
            port=number("--port",-1,1,65535); logDir=Path.of(require("--log-dir")).toAbsolutePath().normalize();
            if(profile.equals("final")&&(clients!=30||requests!=5000||minMs!=200||maxMs!=1000))
                throw new IllegalArgumentException("Final requires 30 clients, 5000 requests, 200..1000 ms");
        }
        String require(String key) { String v=args.get(key); if(v==null||v.isBlank()) throw new IllegalArgumentException("Required "+key); return v; }
        Config session(String id) {
            if(!runId.equals("auto")||id.equals("auto")) throw new IllegalArgumentException("Bad standby session id");
            if(!id.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Bad run id");
            Map<String,String> values=new LinkedHashMap<>(args);
            values.put("--run-id",id);values.put("--log-dir",logDir.resolve(id).toString());
            List<String> argv=new ArrayList<>();values.forEach((key,value)->{argv.add(key);argv.add(value);});
            return new Config(argv.toArray(String[]::new));
        }
        int number(String key,int fallback,int min,int max) {
            int v=Integer.parseInt(args.getOrDefault(key,Integer.toString(fallback)));
            if(v<min||v>max) throw new IllegalArgumentException("Out of range "+key); return v;
        }
        long seed(){return Long.parseLong(args.getOrDefault("--seed","20261009"));}
        String init(String hash) {
            return "run="+runId+" profile="+profile+" clients="+clients+" requests="+requests+" minMs="+minMs+" maxMs="+maxMs+
                " port="+port+" timezone=KST sha256="+hash+" java="+clean(System.getProperty("java.version"))+" os="+clean(System.getProperty("os.name"));
        }
    }
    static final class Log implements AutoCloseable {
        final String node; private final BufferedWriter writer;
        Log(Path path,String node)throws IOException { this.node=node; Files.createDirectories(path.getParent()); writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE); }
        synchronized void event(String event,String status,String message)throws IOException {
            writer.write("["+LocalTime.now(ZONE).format(CLOCK)+"] "+node+" | "+event+" | "+status+" | "+message); writer.newLine(); writer.flush();
        }
        public synchronized void close()throws IOException {writer.close();}
    }
    record Request(int client,int id,String op,int[] seats,String invalid) {
        String fields(){return "client="+client+" req="+id+" op="+op+" seats="+csv(seats);}
    }
    record State(int seat,long version,int owner) { public String toString(){return seat+":"+version+":"+owner;} }
    record Notification(int client,int request,int seat,long version,long registeredNs) {}
    record Reply(String result,String reason,List<State> states,List<String> audit,Notification notification) {
        String statesText(){return states.isEmpty()?"-":String.join(",",states.stream().map(State::toString).toList());}
        String frame(int id){return "RESP "+id+" "+result+" "+reason+" "+statesText();}
    }
    record QueueState(int size,int active,long backlogNs) {}
    /** Queue CV protects dequeue and active transition in the same critical section. */
    static final class Requests {
        private final ArrayDeque<Request> items=new ArrayDeque<>();
        private final ReentrantLock lock=new ReentrantLock(); private final Condition notEmpty=lock.newCondition();
        private final int limit; private boolean closed; private int active,max; private long backlogNs;
        Requests(int limit){this.limit=limit;}
        void put(Request request) throws InterruptedException {
            lock.lockInterruptibly(); try {
                if(closed||items.size()>=limit) throw new IllegalStateException("Request queue closed/full");
                if(items.isEmpty()&&active==0) backlogNs=System.nanoTime(); items.addLast(request); max=Math.max(max,items.size()); notEmpty.signal();
            } finally {lock.unlock();}
        }
        Request take()throws InterruptedException {
            lock.lockInterruptibly(); try {while(items.isEmpty()&&!closed)notEmpty.await(); if(items.isEmpty())return null; active++;return items.removeFirst();}finally{lock.unlock();}
        }
        void finish(){lock.lock();try{active--;if(active<0)throw new IllegalStateException("Negative active");if(items.isEmpty()&&active==0)backlogNs=0;}finally{lock.unlock();}}
        QueueState state(){lock.lock();try{return new QueueState(items.size(),active,backlogNs);}finally{lock.unlock();}}
        int max(){lock.lock();try{return max;}finally{lock.unlock();}}
        void close(){lock.lock();try{closed=true;notEmpty.signalAll();}finally{lock.unlock();}}
    }
    static final class Notifications {
        private final ArrayDeque<Notification> items=new ArrayDeque<>(); private final ReentrantLock lock=new ReentrantLock();
        private final Condition ready=lock.newCondition(); private boolean closed; private final int limit;
        Notifications(int limit){this.limit=limit;}
        void put(Notification n)throws InterruptedException {lock.lockInterruptibly();try{if(closed||items.size()>=limit)throw new IllegalStateException("Notify queue closed/full");items.addLast(n);ready.signal();}finally{lock.unlock();}}
        Notification take()throws InterruptedException {lock.lockInterruptibly();try{while(items.isEmpty()&&!closed)ready.await();return items.pollFirst();}finally{lock.unlock();}}
        int size(){lock.lock();try{return items.size();}finally{lock.unlock();}}
        void close(){lock.lock();try{closed=true;ready.signalAll();}finally{lock.unlock();}}
    }
    @FunctionalInterface interface LineHandler {void accept(String line)throws Exception;}
    static final class Frames {
        private final ByteArrayOutputStream line=new ByteArrayOutputStream();
        void feed(ByteBuffer input,LineHandler handler)throws Exception {
            while(input.hasRemaining()) {
                byte b=input.get(); if(b=='\n') {
                    byte[] bytes=line.toByteArray(); line.reset(); int length=bytes.length;
                    if(length>0&&bytes[length-1]=='\r')length--;
                    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,length)).toString();
                    if(text.isBlank())throw new IOException("Empty frame"); handler.accept(text);
                } else {if(line.size()>=MAX_FRAME)throw new IOException("Frame too long");line.write(b);}
            }
        }
        boolean partial(){return line.size()!=0;}
    }
    static Request parseRequest(int client,String line,int maxId) {
        String[] fields=line.split(" ",-1); if(fields.length<2)throw new IllegalArgumentException("Missing req id");
        int id=Integer.parseInt(fields[1]);if(id<1||id>maxId)throw new IllegalArgumentException("Bad req id");
        String op=fields[0];if(!Set.of("RESERVE","RESERVE_MULTI","CANCEL").contains(op))return new Request(client,id,op,new int[0],"FORMAT");
        if(fields.length!=3)return new Request(client,id,op,new int[0],"FORMAT");
        try{return new Request(client,id,op,parseCsv(fields[2]),null);}catch(NumberFormatException ex){return new Request(client,id,op,new int[0],"FORMAT");}
    }
    /** Reuses the calling thread's Selector. No send relay or extra thread. */
    static void send(SocketChannel channel,ReentrantLock sendLock,Selector writes,String frame)throws Exception {
        byte[] bytes=(frame+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_FRAME+1)throw new IOException("Outgoing frame too long");
        long deadline=System.nanoTime()+SEND_TIMEOUT_NS;
        if(!sendLock.tryLock(SEND_TIMEOUT_NS,TimeUnit.NANOSECONDS))throw new SocketTimeoutException("Send lock timeout");
        SelectionKey key=null;
        try {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining()) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Send interrupted");
                long left=deadline-System.nanoTime();if(left<=0)throw new SocketTimeoutException("Send timeout");
                if(channel.write(buffer)==0) {
                    if(key==null)key=channel.register(writes,SelectionKey.OP_WRITE);
                    writes.select(Math.max(1,Math.min(1000,TimeUnit.NANOSECONDS.toMillis(left)))); writes.selectedKeys().clear();
                }
            }
        } finally {try{if(key!=null){key.cancel();writes.selectNow();writes.selectedKeys().clear();}}finally{sendLock.unlock();}}
    }
    static String csv(int[] values){return values.length==0?"-":String.join(",",Arrays.stream(values).mapToObj(Integer::toString).toList());}
    static int[] parseCsv(String value){if(value.equals("-"))return new int[0];return Arrays.stream(value.split(",",-1)).mapToInt(Integer::parseInt).toArray();}
    static String clean(String text){return text.replaceAll("\\s+","_").replace('=','_').replace('|','_');}
    static String artifactHash()throws Exception {
        Path path=Path.of(Shared.class.getProtectionDomain().getCodeSource().getLocation().toURI());MessageDigest md=MessageDigest.getInstance("SHA-256");
        if(Files.isRegularFile(path)) {try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}}
        else {try(var files=Files.list(path)){for(Path p:files.filter(p->p.toString().endsWith(".class")).sorted().toList())md.update(Files.readAllBytes(p));}}
        return HexFormat.of().formatHex(md.digest());
    }
    static Map<String,String> fields(String message) {Map<String,String> m=new HashMap<>();for(String token:message.split(" ")){int i=token.indexOf('=');if(i>0)m.put(token.substring(0,i),token.substring(i+1));}return m;}
    static void quietly(AutoCloseable c){if(c!=null)try{c.close();}catch(Exception ignored){}}
    private Shared(){}
}

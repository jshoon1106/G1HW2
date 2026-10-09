import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/** Main is the sole listener. Exactly 10 workers + 1 notifier + 1 monitor. */
public final class BookingServer {
    /** 완료 로그의 부분 송신 상태. 기존 Listener에서만 진행하며 Thread 생성 없음. */
    static final class LogDownload implements AutoCloseable {
        static final int CHUNK = 64 * 1024, BUDGET = 256 * 1024;
        final FileChannel file; final long size, startedNs;
        final MessageDigest digest; ByteBuffer pending; long readBytes, progressNs;
        boolean trailer, finished;
        private LogDownload(FileChannel file,long size,String run,String error)throws Exception {
            this.file=file;this.size=size;startedNs=progressNs=System.nanoTime();
            digest=MessageDigest.getInstance("SHA-256");
            pending=ByteBuffer.wrap((error==null?"LOG_BEGIN "+run+" "+size+"\n":"LOG_ERROR "+error+"\n").getBytes(StandardCharsets.UTF_8));
            trailer=error!=null;
        }
        static LogDownload error(String reason)throws Exception{return new LogDownload(null,0,"-",reason);}
        static LogDownload completed(Path root,String run)throws Exception {
            if(!run.matches("[A-Za-z0-9_-]{1,64}")||run.equals("auto"))throw new IllegalArgumentException("INVALID_RUN");
            Path directory=root.resolve(run);
            if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
                throw new NoSuchFileException("NOT_FOUND");
            Path file=directory.resolve("Server.txt");
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new NoSuchFileException("NOT_FOUND");
            FileChannel input=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            try {
                long size=input.size();int length=(int)Math.min(size,4096);
                ByteBuffer tail=ByteBuffer.allocate(length);
                while(tail.hasRemaining()){int n=input.read(tail,size-length+tail.position());if(n<0)throw new EOFException("NOT_READY");}
                String text=new String(tail.array(),StandardCharsets.UTF_8).stripTrailing();
                String last=text.substring(text.lastIndexOf('\n')+1);
                if(!last.contains(" SERVER | TERMINATE | SUCCESS | ")||!last.contains("execution=COMPLETE"))
                    throw new IOException("NOT_READY");
                return new LogDownload(input,size,run,null);
            }catch(Exception e){input.close();throw e;}
        }
        boolean expired(long now){return now-progressNs>TimeUnit.SECONDS.toNanos(30)||now-startedNs>TimeUnit.MINUTES.toNanos(10);}
        boolean writeTo(WritableByteChannel channel,long now)throws Exception {
            int budget=BUDGET;
            while(budget>0&&!finished) {
                if(pending.hasRemaining()) {
                    int n=channel.write(pending);if(n==0)return false;
                    budget-=n;progressNs=now;if(pending.hasRemaining())continue;
                }
                if(trailer){finished=true;return true;}
                if(readBytes<size) {
                    ByteBuffer part=ByteBuffer.allocate((int)Math.min(CHUNK,size-readBytes));
                    int n=file.read(part);if(n<0)throw new EOFException("Log changed during collection");
                    if(n==0)return false;
                    readBytes+=n;part.flip();digest.update(part.asReadOnlyBuffer());pending=part;
                } else {
                    if(file.size()!=size)throw new IOException("Log changed during collection");
                    pending=ByteBuffer.wrap(("LOG_END "+HexFormat.of().formatHex(digest.digest())+"\n").getBytes(StandardCharsets.UTF_8));
                    trailer=true;
                }
            }
            return finished;
        }
        public void close()throws IOException{if(file!=null)file.close();}
    }
    static final class Connection {
        final SocketChannel channel;final String peer;final ReentrantLock sendLock=new ReentrantLock();final Shared.Frames frames=new Shared.Frames();
        final ByteBuffer input=ByteBuffer.allocate(16384);final long acceptedNs=System.nanoTime();
        final BitSet acceptedRequests=new BitSet();final AtomicInteger responses=new AtomicInteger();
        volatile int client;int received;volatile boolean byeSent;boolean management;LogDownload download;
        Connection(SocketChannel c)throws IOException{channel=c;peer=Shared.clean(c.getRemoteAddress().toString());}
    }
    Shared.Config cfg;volatile Shared.Log log;final boolean standby;final Path runLogs;final Shared.Requests requests;final Shared.Notifications notifications;
    final AtomicLong completed=new AtomicLong(),notified=new AtomicLong(),outstanding=new AtomicLong(),lastProgress=new AtomicLong(),lastResponse=new AtomicLong(),waitNs=new AtomicLong();
    final AtomicReference<String> failure=new AtomicReference<>();final AtomicInteger deadlocks=new AtomicInteger();
    final SeatStore store;final ConcurrentHashMap<Integer,Connection> clients=new ConcurrentHashMap<>();
    final ConcurrentHashMap<SocketChannel,Connection> connections=new ConcurrentHashMap<>();
    final List<Thread> workers=new ArrayList<>();final List<Selector> writers=new ArrayList<>();
    final Selector reads,controlWrites;final ServerSocketChannel listening;final Thread listenerThread;
    Thread notifier,monitor;volatile boolean stopping;long firstConnectionNs,lastPoolNs;
    BookingServer(Shared.Config cfg)throws Exception {
        this.cfg=cfg;cfg.require("--bind");
        standby=cfg.runId.equals("auto");runLogs=standby?cfg.logDir:cfg.logDir.getParent();
        if(cfg.profile.equals("final")&&!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux"))
            throw new IllegalArgumentException("Final server must run on remote Linux; use dev locally");
        listenerThread=Thread.currentThread();listenerThread.setName("g1hw2-listener");
        if(!standby)log=new Shared.Log(cfg.logDir.resolve("Server.txt"),"SERVER");requests=new Shared.Requests(cfg.clients*cfg.requests);
        notifications=new Shared.Notifications(cfg.clients*cfg.requests);store=new SeatStore(outstanding);
        reads=Selector.open();controlWrites=Selector.open();listening=ServerSocketChannel.open();
    }
    void fail(String message) {
        if(failure.compareAndSet(null,Shared.clean(message))) {
            System.err.println("SERVER FAIL: "+message);reads.wakeup();listenerThread.interrupt();
            for(Connection c:connections.values()){Shared.quietly(c.download);Shared.quietly(c.channel);}
            requests.close();notifications.close();
            for(Thread w:workers)w.interrupt();if(notifier!=null)notifier.interrupt();if(monitor!=null&&monitor!=Thread.currentThread())monitor.interrupt();
        }
    }
    void worker(Selector writes) {
        while(failure.get()==null) {
            Shared.Request r=null;
            try {
                r=requests.take();if(r==null)return;if(failure.get()!=null)return;
                Shared.Reply reply=store.apply(r);
                for(String event:reply.audit()) {
                    if(event.startsWith("LOCK "))log.event("LOCK","INFO",event.substring(5));
                    else {
                        log.event("AUDIT","INFO",event);
                        if(event.contains("action=WAITLIST"))log.event("WAITLIST","SUCCESS",event);
                    }
                }
                if(reply.notification()!=null)notifications.put(reply.notification());
                Connection c=clients.get(r.client());if(c==null)throw new IOException("Missing client "+r.client());
                Shared.send(c.channel,c.sendLock,writes,reply.frame(r.id()));
                long now=System.nanoTime();c.responses.incrementAndGet();completed.incrementAndGet();lastProgress.accumulateAndGet(now,Math::max);lastResponse.accumulateAndGet(now,Math::max);
                log.event("RESPONSE",reply.result().equals("WAITLISTED")?"INFO":reply.result(),r.fields()+" result="+reply.result()+" reason="+reply.reason()+" states="+reply.statesText());
                log.event(r.op(),reply.result().equals("WAITLISTED")?"INFO":reply.result(),r.fields()+" result="+reply.result());
            } catch(Exception e) {if(!stopping&&failure.get()==null)fail("worker "+e.getClass().getSimpleName()+": "+e.getMessage());return;}
            finally {if(r!=null)requests.finish();}
        }
    }
    void notifyLoop(Selector writes) {
        while(failure.get()==null)try {
            Shared.Notification n=notifications.take();if(n==null)return;
            Connection c=clients.get(n.client());if(c==null)throw new IOException("Missing notification client");
            Shared.send(c.channel,c.sendLock,writes,"NOTIFY "+n.request()+" "+n.seat()+" "+n.version());
            long elapsed=System.nanoTime()-n.registeredNs();
            log.event("NOTIFY","SUCCESS","client="+n.client()+" req="+n.request()+" seat="+n.seat()+" version="+n.version()+" waitNs="+elapsed);
            waitNs.addAndGet(elapsed);notified.incrementAndGet();outstanding.decrementAndGet();reads.wakeup();
        }catch(Exception e){if(!stopping&&failure.get()==null)fail("notifier "+e.getClass().getSimpleName()+": "+e.getMessage());return;}
    }
    void monitorLoop() {
        while(!stopping&&failure.get()==null)try {
            Shared.QueueState state=requests.state();long now=System.nanoTime();
            if(state.size()+state.active()>0) {
                long since=Math.max(state.backlogNs(),lastProgress.get());
                if(since>0&&now-since>TimeUnit.SECONDS.toNanos(30)) {
                    deadlocks.incrementAndGet();
                    log.event("HEALTH","FAIL","queue="+state.size()+" active="+state.active()+" completed="+completed.get()+" outstanding="+outstanding.get()+" reason=NO_PROGRESS_30S");
                    fail("NO_PROGRESS_30S");return;
                }
            }
            // A timed condition-like park for periodic monitoring, not Worker/Notifier polling.
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));if(Thread.interrupted()&&!stopping)throw new InterruptedException();
        }catch(Exception e){if(!stopping&&failure.get()==null)fail("monitor "+e.getMessage());return;}
    }
    void startThreads()throws IOException {
        for(int i=1;i<=Shared.WORKERS;i++){Selector writes=Selector.open();writers.add(writes);Thread w=new Thread(()->worker(writes),"g1hw2-worker-"+i);workers.add(w);}
        Selector notifyWrites=Selector.open();writers.add(notifyWrites);
        notifier=new Thread(()->notifyLoop(notifyWrites),"g1hw2-notifier");monitor=new Thread(this::monitorLoop,"g1hw2-monitor");
        for(Thread w:workers)w.start();notifier.start();monitor.start();
    }
    void accept()throws Exception {
        for(SocketChannel channel;(channel=listening.accept())!=null;) {
            if(connections.size()>=cfg.clients*2){channel.close();continue;}
            channel.configureBlocking(false);channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY,true);
            Connection c=new Connection(channel);connections.put(channel,c);channel.register(reads,SelectionKey.OP_READ,c);
            // 예매 연결의 ACCEPT 기록은 HELLO 식별 후 작성. 수집 연결은 콘솔 전용.
        }
    }
    void message(Connection c,String line)throws Exception {
        if(c.client==0) {
            String[] p=line.split(" ",-1);
            if(c.management)throw new IOException("Unexpected collection input");
            if(p[0].equals("GET_LOG")){beginDownload(c,p);return;}
            if(log==null) {
                if(p.length!=4||!p[0].equals("HELLO")||!p[3].equals(cfg.profile))throw new IOException("Bad standby HELLO/profile");
                int firstId=Integer.parseInt(p[2]);if(firstId<1||firstId>cfg.clients)throw new IOException("Bad client id");
                Shared.Config session=cfg.session(p[1]);
                log=new Shared.Log(session.logDir.resolve("Server.txt"),"SERVER");cfg=session;initialiseLog();

                System.out.println("SERVER SESSION run="+cfg.runId);
            }
            if(p.length!=4||!p[0].equals("HELLO")||!p[1].equals(cfg.runId)||!p[3].equals(cfg.profile))throw new IOException("Bad HELLO/run/profile");
            int id=Integer.parseInt(p[2]);if(id<1||id>cfg.clients||clients.putIfAbsent(id,c)!=null)throw new IOException("Bad/duplicate client id");
            c.client=id;if(firstConnectionNs==0||c.acceptedNs<firstConnectionNs){firstConnectionNs=c.acceptedNs;lastProgress.set(firstConnectionNs);}
            log.event("CONNECT","INFO","phase=ACCEPT peer="+c.peer);
            log.event("CONNECT","SUCCESS","phase=HELLO client="+id);return;
        }
        Shared.Request r=Shared.parseRequest(c.client,line,cfg.requests);
        if(c.acceptedRequests.get(r.id())||c.received>=cfg.requests)throw new IOException("Duplicate/over-quota request");
        c.acceptedRequests.set(r.id());c.received++;log.event("PROTOCOL","INFO","direction=IN "+r.fields());requests.put(r);
    }
    void beginDownload(Connection c,String[] fields)throws Exception {
        c.management=true;String reason=null;
        if(fields.length!=2||!fields[1].matches("[A-Za-z0-9_-]{1,64}")||fields[1].equals("auto"))reason="INVALID_RUN";
        else if(fields[1].equals(cfg.runId))reason="NOT_READY";
        else if(connections.values().stream().filter(other->other.download!=null&&other.download.file!=null).count()>=2)reason="BUSY";
        if(reason==null)try{c.download=LogDownload.completed(runLogs,fields[1]);}
        catch(NoSuchFileException e){reason="NOT_FOUND";}
        catch(IOException e){reason="NOT_READY";}
        if(reason!=null)c.download=LogDownload.error(reason);
        c.channel.keyFor(reads).interestOps(SelectionKey.OP_WRITE);
        System.out.println("SERVER LOG "+(reason==null?"BEGIN run="+fields[1]:"ERROR reason="+reason));
    }
    void closeDownload(Connection c) {
        SelectionKey key=c.channel.keyFor(reads);if(key!=null)key.cancel();
        connections.remove(c.channel);Shared.quietly(c.download);Shared.quietly(c.channel);
    }
    void writeDownload(SelectionKey key) {
        Connection c=(Connection)key.attachment();
        try {
            if(c.download.expired(System.nanoTime()))throw new SocketTimeoutException("Log transfer timeout");
            if(c.download.writeTo(c.channel,System.nanoTime())) {
                if(c.download.file!=null)System.out.println("SERVER LOG SENT bytes="+c.download.size);
                closeDownload(c);
            }
        }catch(Exception e){System.err.println("SERVER LOG FAIL "+Shared.clean(e.toString()));closeDownload(c);}
    }
    void receive(SelectionKey key)throws Exception {
        Connection c=(Connection)key.attachment();
        for(int batch=0;batch<16;batch++) {
            c.input.clear();int count;
            try{count=c.channel.read(c.input);}catch(IOException e){if(c.client==0){reject(c,"IO_BEFORE_HELLO");return;}throw e;}
            if(count<0){if(c.client==0){reject(c,"EOF_BEFORE_HELLO");return;}key.cancel();if(!c.byeSent||c.frames.partial())throw new EOFException("Client disconnected before BYE");Shared.quietly(c.channel);return;}
            if(count==0)return;c.input.flip();
            try{c.frames.feed(c.input,line->message(c,line));}
            catch(Exception e){if(c.client==0){reject(c,e.getClass().getSimpleName()+":"+e.getMessage());return;}try{Shared.send(c.channel,c.sendLock,controlWrites,"ERROR "+Shared.clean(e.getMessage()==null?"PROTOCOL":e.getMessage()));}catch(Exception ignored){}throw e;}
        }
    }
    void reject(Connection c,String reason)throws IOException {
        String details="phase=REJECT peer="+c.peer+" reason="+Shared.clean(reason);
        SelectionKey key=c.channel.keyFor(reads);if(key!=null)key.cancel();connections.remove(c.channel);Shared.quietly(c.download);Shared.quietly(c.channel);
        if(c.management)System.err.println("SERVER LOG FAIL "+details);
        else if(log!=null)log.event("CONNECT","WARN",details);else System.out.println("SERVER REJECT "+details);
    }
    void pool(long now)throws Exception {
        if(log==null)return;
        if(now-lastPoolNs<TimeUnit.SECONDS.toNanos(5))return;lastPoolNs=now;
        List<SeatStore.Snapshot> snap=store.snapshot();Shared.QueueState q=requests.state();
        String seats=String.join(",",snap.stream().map(s->s.seat()+":"+s.owner()+":"+s.waiters().size()).toList());
        log.event("POOL","INFO","queue="+q.size()+" active="+q.active()+" processed="+completed.get()+" maxQueue="+requests.max()+" seats="+seats+" snapshot=per-seat");
        System.out.println("POOL processed="+completed.get()+" queue="+q.size()+" active="+q.active());
    }
    boolean drained() {
        if(clients.size()!=cfg.clients||completed.get()!=(long)cfg.clients*cfg.requests)return false;
        for(Connection c:clients.values())if(c.received!=cfg.requests||c.responses.get()!=cfg.requests)return false;
        Shared.QueueState q=requests.state();return q.size()==0&&q.active()==0&&notifications.size()==0&&outstanding.get()==0;
    }
    void checkTimeouts(long now)throws IOException {
        for(Connection c:connections.values()) {
            if(c.download!=null){if(c.download.expired(now))closeDownload(c);}
            else if(c.client==0&&now-c.acceptedNs>TimeUnit.SECONDS.toNanos(10))reject(c,"HELLO_TIMEOUT");
        }
        if(firstConnectionNs!=0) {
            if(clients.size()<cfg.clients&&now-firstConnectionNs>TimeUnit.SECONDS.toNanos(60))throw new SocketTimeoutException("Client registration timeout");
            if(now-firstConnectionNs>TimeUnit.MINUTES.toNanos(90))throw new SocketTimeoutException("Run timeout");
        }
    }
    void normalEnd()throws Exception {
        List<SeatStore.Snapshot> snap=store.snapshot();Map<Integer,Integer> pending=new HashMap<>();
        for(SeatStore.Snapshot s:snap)for(SeatStore.Waiter w:s.waiters())pending.merge(w.client(),1,Integer::sum);
        for(int id=1;id<=cfg.clients;id++) {
            Connection c=clients.get(id);Shared.send(c.channel,c.sendLock,controlWrites,"BYE "+cfg.runId+" "+pending.getOrDefault(id,0));c.byeSent=true;
            log.event("PROTOCOL","SUCCESS","direction=OUT type=BYE client="+id+" unresolved="+pending.getOrDefault(id,0));
        }
        long assigned=0,released=0;int occupied=0,unresolved=0;
        for(SeatStore.Snapshot s:snap) {
            assigned+=s.assigned();released+=s.released();if(s.owner()!=0)occupied++;unresolved+=s.waiters().size();
            log.event("STAT","INFO","type=FINAL_SEAT seat="+s.seat()+" owner="+s.owner()+" version="+s.version()+" seq="+s.sequence()+" waiting="+s.waitText()+" assigned="+s.assigned()+" released="+s.released());
        }
        if(assigned-released!=occupied)throw new IllegalStateException("Assignment balance mismatch");
        log.event("DOUBLE_BOOKING_CHECK","INFO","scope=server_final balance=PASS client_log_verification=PENDING");
        double seconds=(lastResponse.get()-firstConnectionNs)/1_000_000_000.0;
        log.event("STAT","INFO","type=SUMMARY firstResponses="+completed.get()+" notify="+notified.get()+" unresolved="+unresolved+" assigned="+assigned+" released="+released+" occupied="+occupied+
            " maxQueue="+requests.max()+" contention="+store.contention.get()+" deadlocks="+deadlocks.get()+" elapsedNs="+(lastResponse.get()-firstConnectionNs)+" throughput="+String.format(Locale.ROOT,"%.6f",completed.get()/seconds)+
            " meanWaitSec="+String.format(Locale.ROOT,"%.6f",notified.get()==0?0:waitNs.get()/1_000_000_000.0/notified.get()));
        log.event("STAT","INFO","type=EXECUTION_DRAINED client_log_verification=PENDING");
    }
    int run()throws Exception {
        boolean complete=false;
        try {
            if(log!=null)initialiseLog();
            listening.configureBlocking(false);listening.bind(new InetSocketAddress(cfg.require("--bind"),cfg.port));listening.register(reads,SelectionKey.OP_ACCEPT);
            startThreads();lastPoolNs=System.nanoTime();
            long actualThreads=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().startsWith("g1hw2-")).count();
            if(actualThreads!=13)throw new IllegalStateException("Expected 13 application threads, observed "+actualThreads);
            if(log!=null)log.event("PROTOCOL","INFO","type=READY appThreads="+actualThreads+" port="+cfg.port);
            System.out.println("SERVER "+(standby?"WAITING":"READY")+" bind="+cfg.require("--bind")+":"+cfg.port+" run="+cfg.runId+" threads=13");
            System.out.flush();
            while(failure.get()==null) {
                reads.select(1000);Iterator<SelectionKey> it=reads.selectedKeys().iterator();
                while(it.hasNext()){SelectionKey key=it.next();it.remove();if(!key.isValid())continue;if(key.isAcceptable())accept();else if(key.isReadable())receive(key);else if(key.isWritable())writeDownload(key);}
                if(drained()){normalEnd();complete=true;break;}
                long now=System.nanoTime();checkTimeouts(now);pool(now);
            }
        }catch(Exception e){if(failure.get()==null)fail(e.getClass().getSimpleName()+": "+e.getMessage());}
        finally {
            stopping=true;Thread.interrupted();Shared.quietly(listening);for(Connection c:connections.values()){Shared.quietly(c.download);Shared.quietly(c.channel);}
            requests.close();notifications.close();if(monitor!=null){monitor.interrupt();LockSupport.unpark(monitor);}
            if(!complete){for(Thread w:workers)w.interrupt();if(notifier!=null)notifier.interrupt();}
            for(Thread w:workers)join(w);join(notifier);join(monitor);
            for(Selector writes:writers)Shared.quietly(writes);Shared.quietly(controlWrites);Shared.quietly(reads);
            if(log!=null) {
                if(failure.get()!=null)log.event("TERMINATE","FAIL","reason="+failure.get()+" firstResponses="+completed.get()+" outstanding="+outstanding.get());
                else if(complete)log.event("TERMINATE","SUCCESS","execution=COMPLETE client_log_verification=PENDING appThreads=13");
                log.close();
            }
        }
        return complete&&failure.get()==null?0:1;
    }
    private void initialiseLog()throws Exception {
        log.event("INIT","INFO",cfg.init(Shared.artifactHash())+" workers=10 listener=1 notifier=1 monitor=1 seats=100 queueLimit="+(cfg.clients*cfg.requests)+" bind="+Shared.clean(cfg.require("--bind"))+" standby="+standby);
        if(standby)log.event("PROTOCOL","INFO","type=READY appThreads=13 port="+cfg.port);
    }
    private void join(Thread thread)throws InterruptedException {
        if(thread==null)return;thread.join(10000);if(thread.isAlive()){thread.interrupt();thread.join(5000);if(thread.isAlive())throw new IllegalStateException("Thread did not terminate: "+thread.getName());}
    }
    public static void main(String[] args) {
        int code;try{code=new BookingServer(new Shared.Config(args)).run();}
        catch(IllegalArgumentException e){System.err.println(e.getMessage());code=2;}
        catch(Exception e){e.printStackTrace();code=1;}if(code!=0)System.exit(code);
    }
}

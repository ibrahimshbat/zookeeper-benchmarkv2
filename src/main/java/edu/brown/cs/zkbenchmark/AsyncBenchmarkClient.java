package edu.brown.cs.zkbenchmark;

import java.io.IOException;

import org.apache.log4j.Logger;

import com.netflix.curator.framework.api.CuratorListener;
import com.netflix.curator.framework.listen.ListenerContainer;

import edu.brown.cs.zkbenchmark.ZooKeeperBenchmark.TestType;

public class AsyncBenchmarkClient extends BenchmarkClient {

	private class Monitor { }
	
	TestType _currentType = TestType.UNDEFINED;
	private Monitor _monitor = new Monitor();
	private boolean _asyncRunning;

	private static final Logger LOG = Logger.getLogger(AsyncBenchmarkClient.class);


	public AsyncBenchmarkClient(ZooKeeperBenchmark zkBenchmark, String host, String namespace,
			int attempts, int id,  String clientName) throws IOException {
		super(zkBenchmark, host, namespace, attempts, id, clientName);
	}
	
	
	@Override
	protected void submit(int n, TestType type) {
		ListenerContainer<CuratorListener> listeners = (ListenerContainer<CuratorListener>)_client.getCuratorListenable();
		BenchmarkListener listener = new BenchmarkListener(this);
		listeners.addListener(listener);
		_currentType = type;
		_asyncRunning = true;
		
		submitRequests(n, type);
		
		synchronized (_monitor) {
			while (_asyncRunning) {
				try {
					_monitor.wait();
				} catch (InterruptedException e) {
					LOG.warn("AsyncClient #" + _id + " was interrupted", e);
				}
			}
		}

		listeners.removeListener(listener);
	}

	private void submitRequests(int n, TestType type) {
		try {
			submitRequestsWrapped(n, type);
		} catch (Exception e) {
			// What can you do? for some reason
			// com.netflix.curator.framework.api.Pathable.forPath() throws Exception
			
			//just log the error, not sure how to handle this exception correctly
			LOG.error("Exception while submitting requests", e);
		}
	}
  long rate = 0;
	private void submitRequestsWrapped(int n, TestType type) throws Exception {
		byte data[];
  
		startThroughputTime = System.currentTimeMillis();

		for (int i = 0; i < n; i++) {
			double time = ((double)System.nanoTime() - _zkBenchmark.getStartTime())/1000000000.0;

			switch (type) {
				case READ:
					_client.getData().inBackground(new Double(time)).forPath(_path);
					break;

				case SETSINGLE:
					data = new String(_zkBenchmark.getData() + i).getBytes();
					_client.setData().inBackground(new Double(time)).forPath(
							_path, data);
					break;

				case SETMULTI:
					data = new String(_zkBenchmark.getData() + i).getBytes();
					_client.setData().inBackground(new Double(time)).forPath(
							_path + "/" + (_count % _highestN), data);
					break;

				case CREATE:					
					
					data = new String(_zkBenchmark.getData() + i).getBytes();
					startLatencyTime = System.currentTimeMillis();
					_client.create().inBackground(new Long(startLatencyTime)).forPath(
							_path + "/" + _count, data);
					_highestN++;
         // rate ++;
					break;

				case DELETE:
					_client.delete().inBackground(new Double(time)).forPath(_path + "/" + _count);
					_highestDeleted++;

					if (_highestDeleted >= _highestN) {
						zkAdminCommand("stat");							
						_zkBenchmark.notifyFinished(_id);
						_timer.cancel();
						_count++;
						return;
					}
			}
			_count++;
      //if (rate > 100){
			//	Thread.sleep(10);
			//	rate =0;
		//	}
		}

	}
	
	@Override
	protected void finish() {
		endThroughputTime = System.currentTimeMillis();

		elapsed = endThroughputTime - startThroughputTime;
		synchronized (_monitor) {
			_asyncRunning = false;
			_monitor.notify();
		}
	}

	@Override
	protected void resubmit(int n) {
		submitRequests(n, _currentType);
	}
}

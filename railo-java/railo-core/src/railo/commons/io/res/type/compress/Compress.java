package railo.commons.io.res.type.compress;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import railo.commons.digest.MD5;
import railo.commons.io.CompressUtil;
import railo.commons.io.IOUtil;
import railo.commons.io.SystemUtil;
import railo.commons.io.res.Resource;
import railo.commons.io.res.ResourceProvider;
import railo.commons.io.res.type.ram.RamResourceProviderOld;
import railo.commons.io.res.util.ResourceUtil;
import railo.runtime.config.Config;
import railo.runtime.config.ConfigImpl;
import railo.runtime.engine.ThreadLocalPageContext;
import railo.runtime.op.Caster;

public final class Compress {
	
	public static final int FORMAT_ZIP = CompressUtil.FORMAT_ZIP;
	public static final int FORMAT_TAR = CompressUtil.FORMAT_TAR;
	public static final int FORMAT_TGZ = CompressUtil.FORMAT_TGZ;
	public static final int FORMAT_TBZ2 = CompressUtil.FORMAT_TBZ2;
	
	
	//private final static Map files=new WeakHashMap();
	
	private final Resource ffile;
	//private ResourceProvider ramProvider;
	private long syn=-1;
	private Resource root;
	private Synchronizer synchronizer;
	private long lastMod=-1;
	private long lastCheck=-1;

	private int format;
	private int mode;
	private boolean caseSensitive;
	private Resource temp;
	
	/**
	 * private Constructor of the class, will be invoked be getInstance
	 * @param file
	 * @param format 
	 * @param caseSensitive 
	 */
	public Compress(Resource file, int format, boolean caseSensitive) {
		this.ffile=file;
		this.format=format;
		this.mode=ffile.getMode();
		if(mode==0) mode=0777;
		load(this.caseSensitive=caseSensitive);
	}

	/**
	 * return zip instance matching the zipfile, singelton isnatce only 1 zip for one file
	 * @param zipFile
	 * @param format 
	 * @param caseSensitive 
	 * @return
	 */
	/*public static Compress getInstance(Resource zipFile, int format, boolean caseSensitive) {
		Compress compress=(Compress) files.get(zipFile.getPath());
		if(compress==null) {
			compress=new Compress(zipFile,format,caseSensitive);
			files.put(zipFile.getPath(), compress);
		}
		return compress;ConfigImpl
	}*/
	public static Compress getInstance(Resource zipFile, int format, boolean caseSensitive) {
		ConfigImpl config=(ConfigImpl) ThreadLocalPageContext.getConfig();
		return config.getCompressInstance(zipFile, format, caseSensitive);
	}
	
	
	private synchronized void load(boolean caseSensitive) {
		long actLastMod = ffile.lastModified();
		lastMod=actLastMod;
		lastCheck=System.currentTimeMillis();
		Map args = new HashMap();
		args.put("case-sensitive", Caster.toBoolean(caseSensitive));
		//ramProvider = new RamResourceProviderOld().init("ram",args);
		//root=ramProvider.getResource("/");
		
		if(temp==null){
			String cid="";
			try {
				Config config = ThreadLocalPageContext.getConfig();
				if(config!=null){
					cid=config.getId();
					temp = config.getTempDirectory();
				}
				if(temp==null)temp=SystemUtil.getTempDirectory();

				temp=temp.getRealResource("compress");
				temp=temp.getRealResource(MD5.getDigestAsString(cid+"-"+ffile.getAbsolutePath()));
				if(!temp.exists())temp.createDirectory(true);
				
			}
			catch(Throwable t){}
		}
		
		
		if(temp!=null) {
			String name=Caster.toString(actLastMod)+":"+Caster.toString(ffile.length());
			name=MD5.getDigestAsString(name,name);
			root=temp.getRealResource(name);
			if(actLastMod>0 && root.exists()) return;
			
			
			ResourceUtil.removeChildrenEL(temp);
			//if(root!=null)ResourceUtil.removeChildrenEL(root);
			//String name=CreateUUID.invoke();
			//root=temp.getRealResource(name);
			root.mkdirs();
		}
		else {
			ResourceProvider ramProvider = new RamResourceProviderOld().init("ram",args);
			root=ramProvider.getResource("/");
		}
		_load();
	}
	
	

	private void _load() {
		if(ffile.exists()) {
			try {
				CompressUtil.extract(format, ffile, root);
			} catch (IOException e) {}
		}
		else {
			try {
				ffile.createFile(false);
			} 
			catch (IOException e) {}
			lastMod=ffile.lastModified();
		}
	}

	public Resource getRamProviderResource(String path) {
		long t=System.currentTimeMillis();
		if(t>lastCheck+2000){
			
			lastCheck=t;
			t=ffile.lastModified();
			if((lastMod-t)>10 || (t-lastMod)>10 || root==null || !root.exists()){
				lastMod=t;
				load(caseSensitive);
			}
		}
		return root.getRealResource(path);//ramProvider.getResource(path);
	}

	/**
	 * @return the zipFile
	 */
	public Resource getCompressFile() {
		return ffile;
	}

	public synchronized void synchronize(boolean async) {
		if(!async) {
			doSynchronize();
			return;
		}
		syn=System.currentTimeMillis();
		if(synchronizer==null || !synchronizer.isRunning()) {
			synchronizer=new Synchronizer(this,100);
			synchronizer.start();
		}
	}

	private void doSynchronize() {
		try {
			CompressUtil.compress(format, root.listResources(), ffile, 777);
			//ramProvider=null;
		} 
		catch (IOException e) {}
	}
	
	class Synchronizer extends Thread {
		private Compress zip;
		private int interval;
		private boolean running=true;

		public Synchronizer(Compress zip, int interval) {
			this.zip=zip;
			this.interval=interval; 
		}
		
		public void run() {
			if(FORMAT_TAR==format) runTar(ffile);
			if(FORMAT_TGZ==format) runTGZ(ffile);
			else runZip(ffile);
			
		}

		private void runTGZ(Resource res) {
			GZIPOutputStream gos=null;
			InputStream tmpis=null;
			Resource tmp = SystemUtil.getTempDirectory().getRealResource(System.currentTimeMillis()+"_.tgz");
	        try {
				gos=new GZIPOutputStream(res.getOutputStream());
				// wait for sync		
				while(true) {
					sleepEL();
					if(zip.syn+interval<=System.currentTimeMillis()) break;
				}
				// sync
				tmpis = tmp.getInputStream();
				CompressUtil.compressTar(root.listResources(), tmp, -1);
				CompressUtil.compressGZip(tmpis, gos);
			}
			catch (IOException e) {}
			finally {
				IOUtil.closeEL(gos);
				IOUtil.closeEL(tmpis);
				tmp.delete();
				running=false;
			}
		}
		private void runTar(Resource res) {
			TarArchiveOutputStream tos=null;
			try {
				tos=new TarArchiveOutputStream(res.getOutputStream());
				tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
		        // wait for sync		
				while(true) {
					sleepEL();
					if(zip.syn+interval<=System.currentTimeMillis()) break;
				}
				// sync
				CompressUtil.compressTar(root.listResources(), tos, -1);
			}
			catch (IOException e) {}
			finally {
				IOUtil.closeEL(tos);
				running=false;
			}
		}

		private void runZip(Resource res) {
			ZipOutputStream zos=null;
			try {
				zos=new ZipOutputStream(res.getOutputStream());
				// wait for sync		
				while(true) {
					sleepEL();
					if(zip.syn+interval<=System.currentTimeMillis()) break;
				}
				// sync
				CompressUtil.compressZip(root.listResources(), zos, null);
			}
			catch (IOException e) {}
			finally {
				IOUtil.closeEL(zos);
				running=false;
			}
		}

		private void sleepEL() {
			try {
				sleep(interval);
			} 
			catch (InterruptedException e) {}
		}

		public boolean isRunning() {
			return running;
		}
	}
}

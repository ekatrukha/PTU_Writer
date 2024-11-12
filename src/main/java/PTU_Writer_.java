/**
 *
 *  PTU_Reader, ImageJ plugin for reading .ptu/.pt3 PicoQuant FLIM image data
 *  
 *  Aim : Open and convert Picoquant .ptu/.pt3 image files for FLIM analysis
 *  Use : Simply select your .ptu/.pt3 file and plugin will provide:
 *  1) Lifetime image stack for each channel (1-4). Each frame corresponds
 *     to the specific lifetime value, intensity of pixel is equal 
 *     to the number of photons with this lifetime (during whole acquisition)
 *  2) Intensity and average lifetime images/stacks. Intensity is just
 *     acquisition image/stack by frame (in photons) and in addition,
 *     plugin generates average lifetime image.
 *     Both can be binned. 
 *  
 *     
    License:
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

/**
 *
 * @author Francois Waharte, PICT-IBiSA, UMR144 CNRS - Institut Curie, Paris France (2016, pt3 read)
 * @author Eugene Katrukha, Utrecht University, Utrecht, the Netherlands (2017, ptu read, intensity and average lifetime stacks)
 */



import java.io.*;
import java.nio.*;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.imagej.ImgPlus;
import net.imglib2.img.VirtualStackAdapter;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.RealSum;

import ij.*;
import ij.gui.GenericDialog;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.plugin.*;
import ij.util.Tools;


public class PTU_Writer_ <T extends IntegerType< T >> implements PlugIn {

	 // some type field constants
    final static int tyEmpty8 	   = -65528;//= hex2dec("FFFF0008");
    final static int tyBool8       = 8;// = hex2dec("00000008");
    final static int tyInt8        = 268435464;//hex2dec("10000008");
    final static int tyBitSet64    = 285212680;//hex2dec("11000008");
    final static int tyColor8      = 301989896;//hex2dec("12000008");
    final static int tyFloat8      = 536870920;//hex2dec("20000008");
    final static int tyTDateTime   = 553648136;//hex2dec("21000008");
    final static int tyFloat8Array = 537001983;//hex2dec("2001FFFF");
    final static int tyAnsiString  = 1073872895;//hex2dec("4001FFFF");
    final static int tyWideString  = 1073938431;//hex2dec("4002FFFF");
    final static int tyBinaryBlob  = -1;//hex2dec("FFFFFFFF");
    final static int WRAPAROUND = 65536;
    final static int T3WRAPAROUND = 1024;

    
    // RecordTypes
    final static int rtPicoHarpT3     = 66307;   //hex2dec('00010303');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $03 (T3), HW: $03 (PicoHarp)
    final static int rtPicoHarpT2     = 66051;   //hex2dec('00010203');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $02 (T2), HW: $03 (PicoHarp)
    final static int rtHydraHarpT3    = 66308;   //hex2dec('00010304');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $03 (T3), HW: $04 (HydraHarp)
    final static int rtHydraHarpT2    = 66052;   //hex2dec('00010204');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $02 (T2), HW: $04 (HydraHarp)
    final static int rtHydraHarp2T3   = 16843524;//hex2dec('01010304');% (SubID = $01 ,RecFmt: $01) (V2), T-Mode: $03 (T3), HW: $04 (HydraHarp)
    final static int rtHydraHarp2T2   = 16843268;//hex2dec('01010204');% (SubID = $01 ,RecFmt: $01) (V2), T-Mode: $02 (T2), HW: $04 (HydraHarp)
    final static int rtTimeHarp260NT3 = 66309;   //hex2dec('00010305');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $03 (T3), HW: $05 (TimeHarp260N)
    final static int rtTimeHarp260NT2 = 66053;   //hex2dec('00010205');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $02 (T2), HW: $05 (TimeHarp260N)
    final static int rtTimeHarp260PT3 = 66310;   //hex2dec('00010306');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $03 (T3), HW: $06 (TimeHarp260P)
    final static int rtTimeHarp260PT2 = 66054;   //hex2dec('00010206');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $02 (T2), HW: $06 (TimeHarp260P)
    final static int rtMultiHarpNT3   = 66311;   //hex2dec('00010307');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $03 (T3), HW: $07 (MultiHarp150N)
    final static int rtMultiHarpNT2   = 66055;   //hex2dec('00010207');% (SubID = $00 ,RecFmt: $01) (V1), T-Mode: $02 (T2), HW: $07 (MultiHarp150N)

    /** Main writing buffer **/
    ByteBuffer bBuff=null;

    /** total number of records **/
    int Records=0;
	/** image width**/
	int PixX=0;
	/** image height**/
	int PixY=0;
	/** pixel size in um**/
	double dPixSize=0;
	/** Line start marker**/
	int nLineStart=0;
	/** Line end marker**/
	int nLineStop=0;
	/** Frame marker **/
	int nFrameMark = -1;
	/** if Frame marker is present (NOT A RELIABLE MARKER??)**/
	boolean bFrameMarkerPresent=false;
    
    /** bin size in frames**/
    int nTimeBin=1;
    /** show lifetime stack**/
    boolean bLTOrder=true;
    /** show intensity and average lifetime per frame images**/
	boolean bIntLTImages=true;
    /** bin size in frames**/
    int nTotalBins=0;
    //boolean [] bDataPresent = new boolean[4];
    boolean [] bChannels = new boolean[4]; 
    
    /** acquisition information **/
    StringBuilder stringInfo = new StringBuilder();
    /** acquisition information **/
    String AcquisitionInfo;
    
    /** flag: whether to load just a range of frames**/
    boolean bLoadRange;
    /** min frame number to load**/
    int nFrameMin;
    /** max frame number to load**/
    int nFrameMax;
    /** Lifetime loading option:
     * 0 = whole stack
     * 1 = binned **/
    int nLTload;
    
    boolean isT2;
    /** defines record format depending on device (picoharp/hydraharp, etc) 
     * **/
    int nRecordType;
    int nHT3Version=2;
    
    
    long nsync=0;
	int chan=0;
	int markers =0;
	int dtime=0;
	/** maximum time of photon arrival (int) **/
	int dtimemax;
	long ofltime;
	
	String sFileNameCounts;
	ImgPlus< T > imgIn;
	
	FileOutputStream fos;
	WritableByteChannel fc;
	
	public static String sVersion = "0.0.1";

	
	@SuppressWarnings( "unchecked" )
	@Override
	public void run(String arg) {

	
		Calibration cal;
		
		
		if(arg.equals(""))
		{
			sFileNameCounts = IJ.getFilePath("Open TIF files with photon counts (Z=lifetime)...");
		}
		else
		{
			sFileNameCounts = arg;
		}
		
		final ImagePlus imp = IJ.openVirtual( sFileNameCounts );
		//some basic checks
		if(!(imp.getBitDepth()==8 || imp.getBitDepth()==16))
		{
			IJ.error( "Only 8- and 16-bit input images are supported!" );
			return;
		}
		if(imp.getStackSize()>4096)
		{
			IJ.error( "Stack size should be below 4096 slices." );
			return;			
		}
		
		imgIn = ( ImgPlus< T > ) VirtualStackAdapter.wrap( imp );
		//image parameters
		//int 78020000
		final int imW = imp.getWidth();
		final int imH = imp.getHeight();
		long Records = computeSum(imgIn);
		cal = imp.getCalibration();
		
		//ask for frequency or time resolution
		int nSyncRate = 80 *100000; //in Hz
		double globTRes = 1./nSyncRate;
		
			
		//get location to save
		String sFilenameOut = sFileNameCounts + "_conv";
		SaveDialog sd = new SaveDialog("Save ROIs ", sFilenameOut, ".ptu");
		String path = sd.getDirectory();
	    if (path==null)
	      	return;
	    sFilenameOut = path + sd.getFileName();
	    
	    File outputFileName = new File(sFilenameOut);
	
	    //let's write stuff
		try 
		{
			fos = new FileOutputStream(outputFileName, false);
			fc = Channels.newChannel( fos );
			
			//mandatory header
			//IdentString
			writeString("PQTTTR",8);
			//formatVersionStr
			writeString("00.0.1",8);
			
			//let's put some tags, 
			//hopefully enough info for other readers to read
			
			writeStringTag("CreatorSW_Name", "PTU_Writer");
			writeStringTag("CreatorSW_Version", sVersion);
			writeLongTag("ImgHdr_PixX",imW);
			writeLongTag("ImgHdr_PixY",imH);
			writeDoubleTag("ImgHdr_PixResol",cal.pixelWidth);
			writeLongTag("ImgHdr_LineStart",1);
			writeLongTag("ImgHdr_LineStop",2);
			writeLongTag("ImgHdr_Frame",3);
			writeLongTag("ImgHdr_BiDirect",0);
			writeLongTag("ImgHdr_SinCorrection",0);
			writeLongTag("TTResult_SyncRate",nSyncRate);
			writeDoubleTag("MeasDesc_GlobalResolution",globTRes);
			writeLongTag("TTResultFormat_TTTRRecType",rtPicoHarpT3);
			writeLongTag("TTResultFormat_BitsPerRecord",32);
			writeEmptyTag("Header_End");
			
			long syncCountPerLine = 83200;

			//long nsync = 0;
			int nsync = 1300;
			int chan = 1;
			int markers = 1;
			int dtime = 33;
			
			int recordData = 270599444;
			int recDataTest = 0;
			recDataTest = (nsync&0xFFFF) | ((dtime&0xFFF)<<16) | ((chan&0xF)<<28) | ((markers&0xF)<<16);
//			recDataTest = (nsync&0xFFFF) | ((dtime&0xFFF)<<16) | ((chan&0xF)<<28) | ((markers&0xF)<<16);
			
			fos.close();

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		


	}
	
	void writeStringTag(String sTagName, String sTagValue)throws IOException
	{
		//sTagIdent
		writeString(sTagName,32);
		//nTagIdx
		writeInt(-1);
		
		//nTagTyp
		writeInt( tyAnsiString );
		
		//nTagInt
		writeLong(sTagValue.length());
		
		//sTagString
		writeString(sTagValue,sTagValue.length());
	}
	
	void writeLongTag(String sTagName, long nTagValue)throws IOException
	{
		//sTagIdent
		writeString(sTagName,32);
		//nTagIdx
		writeInt(-1);
		
		//nTagTyp
		writeInt( tyInt8 );
		
		//nTagInt
		writeLong(nTagValue);
	}
	
	void writeDoubleTag(String sTagName, double dTagValue)throws IOException
	{
		//sTagIdent
		writeString(sTagName,32);
		//nTagIdx
		writeInt(-1);
		
		//nTagTyp
		writeInt( tyFloat8 );
		
		//nTagDouble
		writeDouble(dTagValue);
	}
	void writeEmptyTag(String sTagName)throws IOException
	{
		//sTagIdent
		writeString(sTagName,32);
		//nTagIdx
		writeInt(-1);
		
		//nTagTyp
		writeInt( tyEmpty8 );
		
		byte [] out = new byte[8];
		fos.write(out);
	}
	
	void writeString(String s, int nPad) throws IOException
	{
		byte [] out = new byte[nPad];
		byte[] in = s.getBytes(Charset.forName("UTF-8"));
		for(int i=0;i<in.length;i++)
		{
			out[i]=in[i];
		}
		fos.write(out);
	}
	
	void writeInt(int n) throws IOException
	{
		bBuff = ByteBuffer.allocateDirect( 4 );
		bBuff.order(ByteOrder.LITTLE_ENDIAN);
		bBuff.asIntBuffer().put( n );
		fc.write( bBuff );
	}
	
	void writeLong(long n) throws IOException
	{
		bBuff = ByteBuffer.allocateDirect( 8 );
		bBuff.order(ByteOrder.LITTLE_ENDIAN);
		bBuff.asLongBuffer().put( n );
		fc.write( bBuff );
	}
	
	void writeDouble(double d) throws IOException
	{
		bBuff = ByteBuffer.allocateDirect( 8 );
		bBuff.order(ByteOrder.LITTLE_ENDIAN);
		bBuff.asDoubleBuffer().put( d );
		fc.write( bBuff );
	}
	
	public < T extends IntegerType< T > > long computeSum( final Iterable< T > input )
	{
		// Count all values using the RealSum class.
		// It prevents numerical instabilities when adding up millions of pixels
		long sum = 0;
 
		for ( final T type : input )
		{
			sum += type.getInteger() ;	
		}
 
		return sum;
	}
	
	
	
    public static int hex2dec(String s) {
        String digits = "0123456789ABCDEF";
        s = s.toUpperCase();
        int val = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = digits.indexOf(c);
            val = 16*val + d;
        }
        return val;
    }
    
    /** 
	 * Dialog displaying options for loading
	 * **/
	public boolean loadDialog(int nTotFrames) 
	{
		GenericDialog loadDialog = new GenericDialog("FLIM data load parameters");
		
		String [] loadoptions = new String [] {
				"Load whole stack","Load binned"};
		
		loadDialog.addMessage("Total number of frames: " + Integer.toString(nTotFrames) );
		loadDialog.addCheckbox("Load Lifetime ordered stack", Prefs.get("PTU_Reader.bLTOrder", true));
		loadDialog.addChoice("Load option:", loadoptions, Prefs.get("PTU_Reader.LTload", "Load whole stack"));
		loadDialog.addMessage("Loading binned results generates large files");
		loadDialog.addMessage("\n");
		loadDialog.addCheckbox("Load Intensity and Lifetime Average stacks", Prefs.get("PTU_Reader.bIntLTImages", true));
		
		loadDialog.addNumericField("Bin size in frames", Prefs.get("PTU_Reader.nTimeBin", 1), 0, 4, " frames");
		loadDialog.addMessage("\n");
		loadDialog.addCheckbox("Load only frame range (applies to all stacks)", Prefs.get("PTU_Reader.bLoadRange", false));
		if(Prefs.get("PTU_Reader.bLoadRange", false))
			loadDialog.addStringField("Range:", Prefs.get("PTU_Reader.sFrameRange", "1 - 2"));		
		else
			loadDialog.addStringField("Range:", new DecimalFormat("#").format(1) + "-" +  new DecimalFormat("#").format(nTotFrames));		

		loadDialog.setResizable(false);
		loadDialog.showDialog();
		if (loadDialog.wasCanceled())
	        return false;
		
		bLTOrder = loadDialog.getNextBoolean();
		Prefs.set("PTU_Reader.bLTOrder", bLTOrder);
		nLTload = loadDialog.getNextChoiceIndex();
		Prefs.set("PTU_Reader.LTload", loadoptions[nLTload]);
		
		bIntLTImages = loadDialog.getNextBoolean();
		Prefs.set("PTU_Reader.bIntLTImages", bIntLTImages);		
		
		nTimeBin = (int)loadDialog.getNextNumber();
		if(nTimeBin<1 || nTimeBin>nTotFrames)
		{
			IJ.log("Bin size should be in the range from 1 to total frame size, resetting to 1");
			nTimeBin = 1;
		}
		Prefs.set("PTU_Reader.nTimeBin", nTimeBin);
		
		
		bLoadRange = loadDialog.getNextBoolean();
		Prefs.set("PTU_Reader.bLoadRange", bLoadRange);	
		
		//range of frames		
		String sFrameRange = loadDialog.getNextString();
		Prefs.set("PTU_Reader.sFrameRange", sFrameRange);	
		String[] range = Tools.split(sFrameRange, " -");
		double c1 = loadDialog.parseDouble(range[0]);
		double c2 = range.length==2?loadDialog.parseDouble(range[1]):Double.NaN;
		nFrameMin = Double.isNaN(c1)?1:(int)c1;
		nFrameMax = Double.isNaN(c2)?nFrameMin:(int)c2;
		if (nFrameMin<1) nFrameMin = 1;
		if (nFrameMax>nTotFrames) nFrameMax = nTotFrames;
		if (nFrameMin>nFrameMax) {nFrameMin=1; nFrameMax=nTotFrames;}	
		
		return true;
	}
	
	
	/** function  that reads from bBuff buffer header in the PTU format**/
	boolean readPTUHeader()
	{

		byte[] somebytes=new byte[8];
		bBuff.get(somebytes,0,8);
		String IdentString= new String(somebytes);
		//System.out.println("Ident: " + IdentString);
		IJ.log("Ident: " + IdentString);
		IdentString=IdentString.trim();

		if(!IdentString.equals("PQTTTR"))
		{
			IJ.log("Invalid, this is not an PTU file.");
			return false;
		}
		somebytes=new byte[8];
		bBuff.get(somebytes,0,8);
		String formatVersionStr=new String(somebytes);
		//System.out.println("Tag version: " + formatVersionStr);
		IJ.log("Tag version: " + formatVersionStr);
		
		String sTagIdent;
		int nTagIdx;
		int nTagTyp;
		
	    long nTagInt=0;
	    double nTagFloat=0.0;
	    String sEvalName;
	    String sTagString;
	    IJ.log("Reading header...");
		boolean bReadEnd = false;
		while(!bReadEnd)
	    {
			somebytes=new byte[32];
			bBuff.get(somebytes,0,32);
			sTagIdent = new String(somebytes);
			sTagIdent=sTagIdent.trim();
			//System.out.println(sTagIdent);
			somebytes=new byte[4];
			bBuff.get(somebytes,0,4);
			nTagIdx=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
			somebytes=new byte[4];
			bBuff.get(somebytes,0,4);
			nTagTyp=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
			if(nTagIdx>-1)
			{sEvalName =  sTagIdent+"("+Integer.toString(nTagIdx)+"):";}
			else
			{sEvalName =  sTagIdent+":";}
					
			switch(nTagTyp)
			{
			case tyEmpty8:
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				sEvalName =  sEvalName+"<Empty>";
				break;
			case tyBool8:
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				if(nTagInt==0)
					sEvalName =  sEvalName+"FALSE";
				else
					sEvalName =  sEvalName+"TRUE";
				break;
			case tyInt8:
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				sEvalName =  sEvalName+Integer.toString((int)nTagInt);
				break;
			case tyBitSet64:
				//STUB _not_tested_
				System.out.println("tyBitSet64 field, not tested");
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				sEvalName =  sEvalName+Integer.toString((int)nTagInt);
				break;
			case tyColor8:
				//STUB _not_tested_
				System.out.println("tyColor8 field, not tested");
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				sEvalName =  sEvalName+Integer.toString((int)nTagInt);
				break;
			case tyFloat8:
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagFloat =ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getDouble();
				sEvalName = sEvalName+Double.toString(nTagFloat);
				break;
			case tyTDateTime:
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				//nTagFloat =ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				nTagFloat =ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getDouble();
				nTagInt = (long) (nTagFloat);
				nTagInt = (long) ((nTagFloat-719529+693960)*24*3600);//(add datenum(1899,12,30) minus linux tima)*in days -> to seconds
				Date itemDate = new Date(nTagInt*1000);
				sEvalName =sEvalName+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(itemDate);
				break;
			case tyFloat8Array:
				//STUB _not tested_
				System.out.println("tyFloat8Array field, not tested");
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				sEvalName = sEvalName+"<Float array with "+Integer.toString((int)nTagInt/8)+" entries>";
				//just read them out
				somebytes=new byte[(int)nTagInt];
				bBuff.get(somebytes,0,(int)nTagInt);				
				
				break;
			case tyAnsiString:
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				somebytes=new byte[(int)nTagInt];
				bBuff.get(somebytes,0,(int)nTagInt);
				sTagString = new String(somebytes);
				sTagString=sTagString.trim();
				sEvalName =sEvalName+sTagString;
				break;
			case tyWideString:
				//STUB _not tested_
				System.out.println("tyWideString field, not tested");
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				somebytes=new byte[(int)nTagInt];
				bBuff.get(somebytes,0,(int)nTagInt);
				sTagString = new String(somebytes);
				sTagString=sTagString.trim();
				sEvalName =sEvalName+sTagString;
				//return;
				break;
			case tyBinaryBlob:
				System.out.println("tyBinaryBlob field, not tested");
				somebytes=new byte[8];
				bBuff.get(somebytes,0,8);
				nTagInt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
				sEvalName = sEvalName+"<Binary Blob with "+Integer.toString((int)nTagInt)+" bytes>";
				//just read them out
				somebytes=new byte[(int)nTagInt];
				bBuff.get(somebytes,0,(int)nTagInt);	
				//return;
				break;
				
			default:
					IJ.log("Oops, UNCATCHED field!");
			}	
			//log stuff
			IJ.log(sEvalName);
			stringInfo.append(sEvalName+"\n");
			if(sTagIdent.equals("Header_End"))
			{
				bReadEnd=true;
				IJ.log("Finished reading header.");
			}
			if(sTagIdent.equals("ImgHdr_PixX"))
			{
				PixX=(int)nTagInt;				
			}
			if(sTagIdent.equals("ImgHdr_PixY"))
			{
				PixY=(int)nTagInt;				
			}
			if(sTagIdent.equals("ImgHdr_PixResol"))
			{
				dPixSize=nTagFloat;				
			}
			if(sTagIdent.equals("TTResult_NumberOfRecords"))
			{
				Records=(int)nTagInt;
			}
			if(sTagIdent.equals("ImgHdr_LineStart"))
			{
				nLineStart=(int)nTagInt;				
			}
			if(sTagIdent.equals("ImgHdr_LineStop"))
			{
				nLineStop=(int)nTagInt;				
			}
			if(sTagIdent.equals("ImgHdr_Frame"))
			{
				nFrameMark=(int)nTagInt;				
			}	
			if(sTagIdent.equals("TTResultFormat_TTTRRecType"))
			{
				switch ((int)nTagInt)
				{
				case rtPicoHarpT3:
					isT2 = false;
		            IJ.log("PicoHarp T3 data");
					break;
				 case rtPicoHarpT2:
		            isT2 = true;
		            IJ.log("PicoHarp T2 data");
		            break;
		        case rtHydraHarpT3:
		            isT2 = false;
		            IJ.log("HydraHarp V1 T3 data");
		            break;
		        case rtHydraHarpT2:
		            isT2 = true;
		            IJ.log("HydraHarp V1 T2 data");
		            break;
		        case rtHydraHarp2T3:
		            isT2 = false;
		            IJ.log("HydraHarp V2 T3 data");
		            break;
		        case rtHydraHarp2T2:
		            isT2 = true;
		            IJ.log("HydraHarp V2 T2 data");
		            break;
		        case rtTimeHarp260NT3:
		            isT2 = false;
		            IJ.log("TimeHarp260N T3 data");
		            break;
		        case rtTimeHarp260NT2:
		            isT2 = true;
		            IJ.log("TimeHarp260N T2 data");
		            break;
		        case rtTimeHarp260PT3:
		            isT2 = false;
		            IJ.log("TimeHarp260P T3 data");
		            break;
		        case rtTimeHarp260PT2:
		            isT2 = true;
		            IJ.log("TimeHarp260P T2 data");
		            break;
		        case rtMultiHarpNT3:
		            isT2 = false;
		            IJ.log("MultiHarp150N T3 data");
		            break;
		        case rtMultiHarpNT2:
		            isT2 = true;
		            IJ.log("MultiHarp150N T2 data");
		            break;
		        default:
		        	IJ.error("Invalid Record Type!");
		        	return false;
				}
				nRecordType = (int)nTagInt;
				if(nRecordType==rtPicoHarpT3 || nRecordType==rtHydraHarp2T3 || nRecordType==rtMultiHarpNT3|| nRecordType==rtHydraHarp2T3|| nRecordType==rtTimeHarp260NT3 || nRecordType==rtTimeHarp260PT3)
				{
					if(nRecordType==rtHydraHarpT3)
						nHT3Version = 1;
					else
						nHT3Version = 2;
				}
				else
				{
					IJ.error("So far in v.0.0.9 only PicoHarp and HydraHarp are supported (and your file has different record type).\n Send example of PTU file to katpyxa@gmail.com");
		        	return false;
				}
			}
			
			
	    }
		return true;
	}
	
	/** function  that reads from bBuff buffer header in the PT3 format**/
	boolean readPT3Header()
	{
		
		
		/* The following is binary file header information */

		int Curves;
		int BitsPerRecord;
		int RoutingChannels;
		int NumberOfBoards;
		int ActiveCurve;
		int MeasMode;
		int SubMode;
		int RangeNo;
		int Offset;
		int Tacq;				// in ms
		int StopAt;
		int StopOnOvfl;
		int Restart;
		int DispLinLog;
		int DispTimeFrom;		// 1ns steps
		int DispTimeTo;
		int RepeatMode;
		int RepeatsPerCurve;
		int RepeatTime;
		int RepeatWaitTime;

		/* The next is a board specific header */
		int HardwareSerial; 
		int SyncDivider;
		int CFDZeroCross0;
		int CFDLevel0;
		int CFDZeroCross1;
		int CFDLevel1;
		float Resolution;

		/* The next is a TTTR mode specific header */
		int ExtDevices;
		int Reserved1;
		int Reserved2;			
		int CntRate0;
		int CntRate1;
		int StopAfter;
		int StopReason;
		
		int ImgHdrSize;		

		byte[] somebytes=new byte[16];
		bBuff.get(somebytes,0,16);
		String IdentString= new String(somebytes);
		IJ.log("Ident: " + IdentString);
		stringInfo.append("Ident: " + IdentString+"\n");

		somebytes=new byte[6];
		bBuff.get(somebytes,0,6);
		String formatVersionStr=new String(somebytes);
		formatVersionStr=formatVersionStr.trim();
		
		IJ.log("format version: " + formatVersionStr);
		stringInfo.append("format version: " + formatVersionStr+"\n");
		if(!formatVersionStr.equals("2.0"))
		{
			IJ.log("Warning: This program is for version 2.0 only. Aborted.");
			return false;
		}
			
		somebytes=new byte[18];
		bBuff.get(somebytes,0,18);
		String CreatorNameStr=new String(somebytes);
		IJ.log("creator name: " + CreatorNameStr);
		stringInfo.append("creator name: " + CreatorNameStr+"\n");
		
		somebytes=new byte[12];
		bBuff.get(somebytes,0,12);
		String CreatorVersionStr=new String(somebytes);
		IJ.log("creator version: " + CreatorVersionStr);
		stringInfo.append("creator version: " + CreatorVersionStr+"\n");
		
		somebytes=new byte[18];
		bBuff.get(somebytes,0,18);
		String FileTimeStr=new String(somebytes);
		IJ.log("File time: " + FileTimeStr);
		stringInfo.append("File time: " + FileTimeStr+"\n");
		
		somebytes=new byte[2];
		bBuff.get(somebytes,0,2); // just to skip 

		somebytes=new byte[256];
		bBuff.get(somebytes,0,256);
		String CommentStr=new String(somebytes);
		IJ.log("Comment: " + CommentStr);
		stringInfo.append("Comment: " + CommentStr+"\n");

		//*******************************
		// Read T3 Header
		//*******************************

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Curves=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Nb of curves: " + Curves);
		stringInfo.append("Nb of curves: " + Curves+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		BitsPerRecord=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("bits per record: " + BitsPerRecord);
		stringInfo.append("bits per record: " + BitsPerRecord+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		RoutingChannels=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Nb of routing channels: " + RoutingChannels);
		stringInfo.append("Nb of routing channels: " + RoutingChannels+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		NumberOfBoards=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Nb of boards: " + NumberOfBoards);
		stringInfo.append("Nb of boards: " + NumberOfBoards+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		ActiveCurve=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Nb of active curve: " + ActiveCurve);
		stringInfo.append("Nb of active curve: " + ActiveCurve+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		MeasMode=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Measurement mode: " + MeasMode);
		stringInfo.append("Measurement mode: " + MeasMode+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		SubMode=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("SubMode: " + SubMode);
		stringInfo.append("SubMode: " + SubMode+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		RangeNo=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("RangeNo: " + RangeNo);
		stringInfo.append("RangeNo: " + RangeNo+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Offset=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Offset (ns): " + Offset);
		stringInfo.append("Offset (ns): " + Offset+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Tacq=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Acquisition time (ms): " + Tacq);
		stringInfo.append("Acquisition time (ms): " + Tacq+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		StopAt=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("StopAt (counts): " + StopAt);
		stringInfo.append("StopAt (counts): " + StopAt+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		StopOnOvfl=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Stop On Overflow: " + StopOnOvfl);
		stringInfo.append("Stop On Overflow: " + StopOnOvfl+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Restart=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Restart: " + Restart);
		stringInfo.append("Restart: " + Restart+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		DispLinLog=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Display Lin/Log: " + DispLinLog);
		stringInfo.append("Display Lin/Log: " + DispLinLog+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		DispTimeFrom=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Display Time Axis From (ns): " + DispTimeFrom);
		stringInfo.append("Display Time Axis From (ns): " + DispTimeFrom+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		DispTimeTo=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Display Time Axit To (ns): " + DispTimeTo);
		stringInfo.append("Display Time Axit To (ns): " + DispTimeTo+"\n");

		somebytes=new byte[108];
		bBuff.get(somebytes,0,108); // Skipping display parameters

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		RepeatMode=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Repeat Mode: " + RepeatMode);
		stringInfo.append("Repeat Mode: " + RepeatMode+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		RepeatsPerCurve=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Repeats Per Curve: " + RepeatsPerCurve);
		stringInfo.append("Repeats Per Curve: " + RepeatsPerCurve+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		RepeatTime=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("RepeatTime: " + RepeatTime);
		stringInfo.append("RepeatTime: " + RepeatTime+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		RepeatWaitTime=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("RepeatWaitTime: " + RepeatWaitTime);
		stringInfo.append("RepeatWaitTime: " + RepeatWaitTime+"\n");

		somebytes=new byte[20];
		bBuff.get(somebytes,0,20);
		String ScriptNameStr=new String(somebytes);
		IJ.log("ScriptName: " + ScriptNameStr);
		stringInfo.append("ScriptName: " + ScriptNameStr+"\n");


		//*******************************
		// Read Board Header
		//*******************************

		somebytes=new byte[16];
		bBuff.get(somebytes,0,16);
		String HardwareStr=new String(somebytes);
		IJ.log("Hardware Identifier: " + HardwareStr);
		stringInfo.append("Hardware Identifier: " + HardwareStr+"\n");
		
		somebytes=new byte[8];
		bBuff.get(somebytes,0,8);
		String HardwareVer=new String(somebytes);
		IJ.log("Hardware Version: " + HardwareVer);
		stringInfo.append("Hardware Version: " + HardwareVer+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		HardwareSerial=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("HardwareSerial: " + HardwareSerial);
		stringInfo.append("HardwareSerial: " + HardwareSerial+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		SyncDivider=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("SyncDivider: " + SyncDivider);
		stringInfo.append("SyncDivider: " + SyncDivider+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		CFDZeroCross0=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("CFDZeroCross (Ch0), (mV): " + CFDZeroCross0);
		stringInfo.append("CFDZeroCross (Ch0), (mV): " + CFDZeroCross0+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		CFDLevel0=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("CFD Discr (Ch0), (mV): " + CFDLevel0);
		stringInfo.append("CFD Discr (Ch0), (mV): " + CFDLevel0+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		CFDZeroCross1=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("CFD ZeroCross (Ch1), (mV): " + CFDZeroCross1);
		stringInfo.append("CFD ZeroCross1 (Ch0), (mV): " + CFDZeroCross1+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		CFDLevel1=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("CFD Discr (Ch1), (mV): " + CFDLevel1);
		stringInfo.append("CFD Discr (Ch1), (mV): " + CFDLevel1+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Resolution = ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getFloat();
		IJ.log("Resolution (ns): " + Resolution);
		stringInfo.append("Resolution (ns): " + Resolution+"\n");

		somebytes=new byte[104];
		bBuff.get(somebytes,0,104); // Skip router settings

		//*******************************
		// Read Specific T3 Header
		//*******************************

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		ExtDevices=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("ExtDevices: " + ExtDevices);
		stringInfo.append("ExtDevices: " + ExtDevices+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Reserved1=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Reserved1: " + Reserved1);
		stringInfo.append("Reserved1: " + Reserved1+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Reserved2=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Reserved2: " + Reserved2);
		stringInfo.append("Reserved2: " + Reserved2+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		CntRate0=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Count Rate (Ch0) (Hz): " + CntRate0);
		stringInfo.append("Count Rate (Ch0) (Hz): " + CntRate0+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		CntRate1=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Count Rate (Ch1) (Hz): " + CntRate1);
		stringInfo.append("Count Rate (Ch1) (Hz): " + CntRate1+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		StopAfter=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Stop After (ms): " + StopAfter);
		stringInfo.append("StopAfter (ms): " + StopAfter+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		StopReason=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("StopReason: " + StopReason);
		stringInfo.append("StopReason: " + StopReason+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		Records=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Records: " + Records);
		stringInfo.append("Records: " + Records+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		ImgHdrSize=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Imaging Header Size (bytes): " + ImgHdrSize);
		stringInfo.append("Imaging Header Size (bytes): " + ImgHdrSize+"\n");
		
		if(ImgHdrSize==0)
		{
			IJ.error("Not a FLIM image file!");
			return false;
		}
		//*******************************
		// Read Imaging Header
		//*******************************
		//	somebytes=new byte[ImgHdrSize*4];
		//	bBuff.get(somebytes,0,ImgHdrSize*4); // Skipping the Imaging header

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		int Dimensions=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Dimensions: " + Dimensions);
		stringInfo.append("Dimensions: " + Dimensions+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		int IdentImg=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("IdentImg: " + IdentImg);
		stringInfo.append("IdentImg: " + IdentImg+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		nFrameMark=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Frame mark: " + nFrameMark);
		stringInfo.append("Frame: " + nFrameMark+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		nLineStart=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("LineStart: " + nLineStart);
		stringInfo.append("LineStart: " + nLineStart+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		nLineStop=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("LineStop: " + nLineStop);
		stringInfo.append("LineStop: " + nLineStop+"\n");

		somebytes=new byte[1];
		bBuff.get(somebytes,0,1);
		int Pattern=somebytes[0];
		IJ.log("Pattern: " + Pattern);
		stringInfo.append("Pattern: " + Pattern+"\n");

		somebytes=new byte[3];
		bBuff.get(somebytes,0,3); //Skipping TCPIP Protocol parameters

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		PixX=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Image width (px): " + PixX);
		stringInfo.append("Image width (px): " + PixX+"\n");

		somebytes=new byte[4];
		bBuff.get(somebytes,0,4);
		PixY=ByteBuffer.wrap(somebytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		IJ.log("Image height (px): " + PixY);
		stringInfo.append("Image height (px): " + PixY+"\n");

		somebytes=new byte[(ImgHdrSize-8)*4];
		bBuff.get(somebytes,0,(ImgHdrSize-8)*4); //Skipping TCPIP Protocol parameters

		return true;
	
	}
	
	/** returns true if it is a photon data, returns false if it is a marker **/
	boolean ReadPT3(int recordData)//, long nsync, int dtime, int chan, int markers)
	{
		boolean isPhoton=true;
		nsync= recordData&0xFFFF; //lowest 16 bits
		dtime=(recordData>>>16)&0xFFF;
		chan=(recordData>>>28)&0xF;

		if (chan== 15)
		{	
			isPhoton=false;
			markers =(recordData>>16)&0xF;			
			if(markers==0 || dtime==0)
			{
				ofltime+=WRAPAROUND;
			}
		}
		return isPhoton;
	}
	
	/** returns true if it is a photon data, returns false if it is a marker **/
	boolean ReadHT3(int recordData)//, long nsync, int dtime, int chan, int markers)
	{
		int special;
	
		boolean isPhoton=true;
		nsync= recordData&0x3FF;//lowest 10 bits
		dtime=(recordData>>>10)&0x7FFF;
		chan = (recordData>>>25)&0x3F;
		special = (recordData>>>31)&0x1;
		special=special*chan;
		if (special==0)
		{
			/*if(chan==0)
				return false;
			else
			*/
			chan=chan+1;
			return isPhoton;
		}
		else
		{
			isPhoton = false;
			if(chan == 63)
			{
				if(nsync==0 || nHT3Version == 1)
					ofltime=ofltime+T3WRAPAROUND;
				else
					ofltime=ofltime+T3WRAPAROUND*nsync;
			}
			
			if ((chan >= 1) && (chan <= 15)) // these are markers
			{
					markers= chan;
			}
		}
					
		
		return isPhoton;
	}
	
	
	public static void main( final String[] args )
	{
		new ImageJ();
		PTU_Writer_ wri = new PTU_Writer_();
		wri.run( "/home/eugene/Desktop/projects/PTU_reader/20231117_image_sc/Example_image.sc_C1_LifetimeAll.tif" );
	}
	/*
	nsync= recordData&0x3FF;//lowest 10 bits
	dtime=(recordData>>>10)&0x7FFF;
	chan = (recordData>>>25)&0x3F;
	special = (recordData>>>31)&0x1;
	if (special==0)
	{
		
	}
	else
	{
		if(chan == 63)
		{
			if(nsync==0 || nHT3Version == 1)
				ofltime=ofltime+T3WRAPAROUND;
			else
				ofltime=ofltime+T3WRAPAROUND*nsync;
		}
	 */
}

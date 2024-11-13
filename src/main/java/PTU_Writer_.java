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


import net.imagej.ImgPlus;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.VirtualStackAdapter;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import ij.*;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.plugin.*;


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

  
    
    int nsync = 0;
	int chan = 0;
	int markers = 0;
	int dtime = 0;
	/** maximum time of photon arrival (int) **/
	int dtimemax;
	long ofltime;
	
	int nLineStart = 1;
	int nLineStop = 2;
	
	String sFileNameCounts;
	ImgPlus< T > imgIn;
	
	FileOutputStream fos;
	WritableByteChannel fc;
	
	public static String sVersion = "0.0.1";
	long RecordsTest = 0;
	int x,y;
	int nCount = 10;
	
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

		
		imgIn = ( ImgPlus< T > ) VirtualStackAdapter.wrap( imp );

		//image parameters		
		final int imW = imp.getWidth();
		final int imH = imp.getHeight();
		
		long nTotPhotons = computeSum(imgIn);
		int nMaxPhotonPerPixel = (int)maxPhoton(imgIn);
		int nMaxPixelCount = nMaxPhotonPerPixel+2;
		//top boundary estimate
		int syncCountPerLine = (nMaxPixelCount)*imW;
		//let's count all records
		long Records = nTotPhotons + imH*2;
		//let's see how many wraparound events we need
		int totWraps = ( int ) Math.floor(((long)syncCountPerLine)*imH/WRAPAROUND);
		Records += totWraps;
		//frame marker
		Records++;
		
		cal = imp.getCalibration();
		
		//ask for frequency or time resolution
		int nSyncRate = 80 *100000; //in Hz
		double globTRes = 1./nSyncRate;
		double dMeasDesc_Resolution = globTRes/132.0;
			
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
			writeLongTag("ImgHdr_LineStart",nLineStart);
			writeLongTag("ImgHdr_LineStop",nLineStop);
			writeLongTag("ImgHdr_Frame",3);
			writeLongTag("ImgHdr_BiDirect",0);
			writeLongTag("ImgHdr_SinCorrection",0);
			writeLongTag("MeasDesc_BinningFactor",1);			
			writeDoubleTag("MeasDesc_Resolution", dMeasDesc_Resolution);
			writeLongTag("TTResult_SyncRate",nSyncRate);
			writeDoubleTag("MeasDesc_GlobalResolution",globTRes);
			writeLongTag("TTResult_NumberOfRecords",Records);
			writeLongTag("TTResultFormat_TTTRRecType",rtPicoHarpT3);
			writeLongTag("TTResultFormat_BitsPerRecord",32);
			writeEmptyTag("Header_End");
			

			//let's cycle through the image
			nsync = 0;
			ofltime = 0;
			chan = 1;
			int nPixVal;
			Cursor< T> cursor;// = iterRAI.cursor();
			for( y = 0; y<imH; y++)

			//for(int y = 0; y<imH; y++)
			{
				//send start frame signal
				writeLineStart();
				for(x=0;x<imW; x++)

	//			for(int x=0;x<imW; x++)
				{
					//get Z column at the current location
					IterableInterval< T > pixTime = getZColumn(imgIn,x,y);
					//write all photons
					cursor = pixTime.cursor();
					dtime = 0;
					while (cursor.hasNext())
					{
						cursor.fwd();
						nPixVal = cursor.get().getInteger();
						while(nPixVal>0)
						{
							//increaseGlobTime(1);
							writePhoton(dtime);
							nPixVal--;
						}
						dtime ++;
					}
					//switch to the new pixel
					if(y<(imH-1))
					{
						setNsyncGlob(y*syncCountPerLine+(x+1)* nMaxPixelCount);
						//setGlobTime(y*syncCountPerLine+(x+1)* nMaxPixelCount);
					}
				}
				setNsyncGlob((y+1)*syncCountPerLine);
		     	//setGlobTime((y+1)*syncCountPerLine);
	
				writeLineStop();
				//increaseGlobTime(1);
				
				
			}
			increaseGlobTime(1);
			writeFrameMarker();
			fos.close();

		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		


	}
	void writePhoton(int dtime_)  throws IOException
	{
		int record = makeRecord(nsync,chan,0,dtime_);
		writeInt(record);
		RecordsTest++;
		if(nCount >0 && ofltime>WRAPAROUND)
		{
			nCount--;
			System.out.println(ofltime +" "+nsync+" "+ x+" "+y+" "+dtime_);
		}
	}
	void setGlobTime(long newTime) throws IOException
	{
		if(newTime<ofltime)
		{
			System.err.println("something went wront, new time is smaller than old one");
		}
		if(newTime>ofltime)
		{
			increaseGlobTime((int)(newTime-ofltime));
		}
	}
	void increaseGlobTime(int inc) throws IOException
	{
		ofltime+=inc;
		increaseNsync(inc);
	}
	void setNsyncGlob(long newTime) throws IOException
	{
		long currTime = ofltime+nsync;
		if(newTime<currTime)
		{
			System.err.println("something went wront, new time is smaller than old one");
		}
		else
		{
			if(newTime>currTime)
			{
				increaseNsync((int)(newTime-currTime));
			}
		}
	}
	void increaseNsync(int inc)  throws IOException
	{
		nsync += inc;
		if(nsync>=WRAPAROUND)
		{
			int nNumW = ( int ) Math.floor( nsync/WRAPAROUND );
			for(int i=0;i<nNumW; i++)
			{
				writeWrapAround(0);
				ofltime+=WRAPAROUND;
			}
			nsync = nsync%WRAPAROUND;
		}
	}
	void writeWrapAround(int nsync_) throws IOException
	{
		int record = makeRecord(nsync_,15,0,0);
		writeInt(record);
		RecordsTest++;
	}
	void writeLineStart() throws IOException
	{
		int record = makeRecord(nsync,15,nLineStart, 0);
		writeInt(record);
		RecordsTest++;
	}
	void writeLineStop() throws IOException
	{
		int record = makeRecord(nsync,15,nLineStop, 0);
		writeInt(record);
		RecordsTest++;
	}
	void writeFrameMarker() throws IOException
	{
		int record = makeRecord(nsync, 15, 4, 0);
		writeInt(record);
		RecordsTest++;
	}
	int makeRecord(int nsync_, int chan_, int markers_, int dtime_)
	{
		return (nsync_&0xFFFF) | ((dtime_&0xFFF)<<16) | ((chan_&0xF)<<28) | ((markers_&0xF)<<16);
	}
	
	void writeStringTag(String sTagName, String sTagValue) throws IOException
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
	
	public static < T extends IntegerType< T > > long computeSum( final Iterable< T > input )
	{
		long sum = 0;
 
		for ( final T type : input )
		{
			sum += type.getInteger() ;	
		}
 
		return sum;
	}
	
	public static < T extends IntegerType< T > > long maxPhoton( final RandomAccessibleInterval< T > input )
	{
		long [] dims = input.dimensionsAsLongArray();
		long max = (-1)*Long.MAX_VALUE;
		long currMax;
		for(int i=0; i<dims[0];i++)
		{
			for(int j=0; j<dims[1];j++)
			{
				currMax = computeSum(Views.hyperSlice( Views.hyperSlice( input, 1, j ),0,i));
				if (currMax>max)
				{
					max = currMax;
				}
			}			
		} 
		return max;
	}
	
	public static < T extends IntegerType< T > > IterableInterval< T >  getZColumn(final RandomAccessibleInterval< T > input, int i, int j)
	{
		return Views.flatIterable( Views.hyperSlice( Views.hyperSlice( input, 1, j ),0,i));
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
	
	
	public static void main( final String[] args )
	{
		new ImageJ();
		PTU_Writer_ wri = new PTU_Writer_();
		wri.run( "/home/eugene/Desktop/projects/PTU_reader/20231117_image_sc/Example_image.sc_C1_LifetimeAll.tif" );
	}

}

package com.termux.ui.desktop.archive;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.apache.commons.compress.archivers.tar.TarUtils;

/** Checks physical records independently of Commons Compress's permissive tar EOF handling. */
final class StrictDesktopTarInput extends InputStream {
    private final InputStream input;
    private final DesktopArchiveLimits limits;
    private final byte[] record=new byte[512];
    private int position,zeros,physicalEntries;
    private long remaining,total,afterEnd;
    private ByteArrayOutputStream pax;
    private boolean eof,ended,pendingPax;

    StrictDesktopTarInput(InputStream input,DesktopArchiveLimits limits) { this.input=input;this.limits=limits; }
    @Override public int read() throws IOException {
        byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;
    }
    @Override public int read(byte[] data,int offset,int length) throws IOException {
        if(length==0)return 0;
        if(eof)return -1;
        int n=input.read(data,offset,Math.min(length,512-position));
        if(n<0){eof=true;requireComplete();return -1;}
        if(n==0)return 0;
        long overhead=(long)limits.maxEntries*(4096+2048L)+limits.maxIndexBytes*4L+11264;
        if(total>Long.MAX_VALUE-n || total+n>safeLimit(limits.maxExpandedBytes,overhead))
            throw fail("archive_tar_stream_limit");
        total+=n;System.arraycopy(data,offset,record,position,n);position+=n;
        if(position==512){validateRecord();position=0;}
        return n;
    }
    private void validateRecord() throws IOException {
        if(remaining>0){
            int used=(int)Math.min(512,remaining);
            if(pax!=null)pax.write(record,0,used);
            for(int i=used;i<512;i++)if(record[i]!=0)throw fail("archive_nonzero_entry_padding");
            remaining-=used;
            if(remaining==0&&pax!=null){validatePax(pax.toByteArray());pax=null;}
            return;
        }
        boolean zero=true;for(byte b:record)if(b!=0){zero=false;break;}
        if(zero){if(pendingPax)throw fail("archive_dangling_pax");ended=true;zeros++;afterEnd+=512;if(afterEnd>11264)throw fail("archive_excess_tar_padding");return;}
        if(ended)throw fail("archive_data_after_tar_end");
        if(!TarUtils.verifyCheckSum(record))throw fail("archive_tar_checksum");
        if(++physicalEntries>(long)limits.maxEntries*2+4)throw fail("archive_entry_limit");
        long size;
        try{size=TarUtils.parseOctalOrBinary(record,124,12);}catch(IllegalArgumentException e){throw fail("archive_invalid_tar_size");}
        if(size<0||size>limits.maxExpandedBytes)throw fail("archive_byte_limit");
        byte kind=record[156];
        if(kind!='0'&&kind!=0&&kind!='5'&&kind!='1'&&kind!='2'&&kind!='x')throw fail("archive_unsupported_tar_header");
        if(kind!='0'&&kind!=0&&kind!='x'&&size!=0)throw fail("archive_invalid_entry_size");
        if(kind=='x'){
            if(pendingPax)throw fail("archive_duplicate_pax");
            pendingPax=true;
            if(size<1||size>65536)throw fail("archive_pax_limit");
            pax=new ByteArrayOutputStream((int)size);
        } else pendingPax=false;
        remaining=size;
    }
    private static void validatePax(byte[] data)throws IOException {
        int offset=0;Set<String> keys=new HashSet<>();
        while(offset<data.length){
            int space=offset;while(space<data.length&&data[space]!=' ')space++;
            if(space==offset||space==data.length||space-offset>8)throw fail("archive_invalid_pax");
            int length=0;
            for(int i=offset;i<space;i++){
                if(data[i]<'0'||data[i]>'9')throw fail("archive_invalid_pax");
                length=length*10+data[i]-'0';
            }
            if(length<=space-offset+2||length>data.length-offset||data[offset+length-1]!='\n')throw fail("archive_invalid_pax");
            String text;
            try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data,space+1,length-(space-offset)-2)).toString();}
            catch(CharacterCodingException e){throw fail("archive_invalid_pax_utf8");}
            int eq=text.indexOf('=');if(eq<1)throw fail("archive_invalid_pax");
            String key=text.substring(0,eq),value=text.substring(eq+1);
            if(!Arrays.asList("path","linkpath","TERMUX.hardlink").contains(key)||!keys.add(key)
                    ||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>4096)
                throw fail("archive_unsupported_pax");
            if(key.equals("TERMUX.hardlink")&&!value.equals("1"))throw fail("archive_invalid_hardlink_source");
            offset+=length;
        }
    }
    void requireComplete() throws IOException {
        if(position!=0||remaining!=0||pax!=null||zeros<2)throw fail("archive_truncated_tar");
    }
    private static long safeLimit(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
    private static DesktopArchiveException fail(String code){return new DesktopArchiveException(code);}
}

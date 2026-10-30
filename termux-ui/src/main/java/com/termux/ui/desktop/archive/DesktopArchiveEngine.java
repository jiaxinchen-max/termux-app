package com.termux.ui.desktop.archive;

import com.termux.ui.domain.DesktopDistro;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.*;

/** Streaming, versioned container archives. No shell, package installation, or host restoration. */
public final class DesktopArchiveEngine {
    private static final String ROOT="payload/container";
    private static final String RECEIPT=".desktop-import-receipt";
    private static final String LINK_SOURCE="TERMUX.hardlink";
    private static final long RETAINED_METADATA_LIMIT=32L*1024*1024;
    private final DesktopArchiveFileSystem fs;
    private final DesktopArchiveLimits limits;

    public DesktopArchiveEngine(DesktopArchiveFileSystem fs, DesktopArchiveLimits limits) {
        this.fs=Objects.requireNonNull(fs); this.limits=Objects.requireNonNull(limits);
    }
    public DesktopArchiveMetadata readMetadata(File archive, String expectedPackage,
                                               String expectedArch) throws IOException {
        return readMetadata(archive,expectedPackage,expectedArch,DesktopArchiveControl.NONE);
    }
    public DesktopArchiveMetadata readMetadata(File archive, String expectedPackage,
            String expectedArch, DesktopArchiveControl control) throws IOException {
        return read(archive,null,expectedPackage,expectedArch,null,control).metadata;
    }
    public void exportArchive(File containerDir, File target, DesktopArchiveMetadata metadata,
                              DesktopArchiveExportMode mode, DesktopArchiveControl control) throws IOException {
        if (mode!=DesktopArchiveExportMode.DIRECT_EXCLUSIVE) throw failure("archive_export_mode_invalid");
        Objects.requireNonNull(metadata); Objects.requireNonNull(control);
        requireDirectory(containerDir);
        if (!containerDir.getName().equals(metadata.getDesktop().getProotDistro()))
            throw failure("archive_container_alias_mismatch");
        requireDirectory(target.getAbsoluteFile().getParentFile());
        DesktopArchiveFileSystem.Stat created=null;
        boolean complete=false;
        Throwable pending=null;
        try {
            Progress progress=new Progress(control,"desktop_archive_export");
            State state=new State(metadata,null,progress);
            byte[] manifest=metadata.encode();
            if (manifest.length>limits.maxIndexBytes) throw failure("archive_metadata_limit");
            try (DesktopArchiveFileSystem.ExclusiveOutput output=fs.createExclusiveFile(target)) {
                created=output.getIdentity();
                if (created==null || created.kind!=DesktopArchiveFileSystem.Kind.FILE)
                    throw failure("archive_output_identity_invalid");
                control.createdOutput(target,created.device,created.inode);
                // Closing the encoders must finish gzip before fsync, without closing its FD.
                OutputStream borrowed=new FilterOutputStream(output) {
                    @Override public void close() throws IOException { flush(); }
                    @Override public void write(byte[] b,int offset,int length)throws IOException {
                        out.write(b,offset,length);
                    }
                };
                try (LimitOutput counted=new LimitOutput(borrowed,limits.maxArchiveBytes);
                     GzipCompressorOutputStream gzip=new GzipCompressorOutputStream(counted);
                     TarArchiveOutputStream tar=new TarArchiveOutputStream(gzip,512,"UTF-8")) {
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
                    putJson(tar,"manifest.json",manifest);
                    writeTree(tar,containerDir,containerDir,"",state,new HashMap<>());
                    state.validateContainer();
                    putJson(tar,"integrity.json",state.integrity(manifest));
                    tar.finish();
                }
                progress.finish(state.bytes);
                output.sync();
            }
            DesktopArchiveFileSystem.Stat current=fs.lstat(target);
            if (current==null || current.kind!=DesktopArchiveFileSystem.Kind.FILE
                    || current.device!=created.device || current.inode!=created.inode)
                throw failure("archive_output_identity_changed");
            complete=true;
        } catch (IOException | RuntimeException | Error e) {
            pending=e;throw e;
        } finally {
            if (!complete && created!=null) try {
                DesktopArchiveFileSystem.Stat current=fs.lstat(target);
                if (current!=null && current.kind==DesktopArchiveFileSystem.Kind.FILE
                        && current.device==created.device && current.inode==created.inode
                        && !target.delete()) throw failure("archive_output_cleanup_failed");
            } catch (IOException cleanup) {
                if (pending!=null) pending.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }
    public DesktopArchiveImportResult importArchive(File archive, File containersRoot,
            String newDesktopId, String newAlias, DesktopArchiveMetadata inspected,
            DesktopArchiveControl control) throws IOException {
        Objects.requireNonNull(inspected); Objects.requireNonNull(control);
        if (inspected.getArchiveSha256()==null) throw failure("archive_inspection_required");
        validateAlias(newAlias);
        if (!DesktopDistro.isValidId(newDesktopId) || ".".equals(newDesktopId)
                || "..".equals(newDesktopId) || newDesktopId.equals(inspected.getDesktop().getId())
                || newAlias.equals(inspected.getDesktop().getProotDistro()))
            throw failure("archive_requires_new_identity");
        requireDirectory(containersRoot);
        File stage=staging(containersRoot,newAlias), target=new File(containersRoot,newAlias);
        File lockFile=new File(containersRoot,".desktop-archive.lock");
        checkLockFile(lockFile);
        try (FileOutputStream lockOutput=new FileOutputStream(lockFile,true);
             FileChannel channel=lockOutput.getChannel(); FileLock ignored=channel.lock()) {
            requireAbsent(target); requireAbsent(stage);
            if (!stage.mkdir()) throw failure("archive_staging_create_failed");
            DesktopArchiveFileSystem.Stat ownedStage=fs.lstat(stage);
            if (ownedStage==null || ownedStage.kind!=DesktopArchiveFileSystem.Kind.DIRECTORY)
                throw failure("archive_staging_identity_invalid");
            boolean published=false;
            try {
                control.createdImportStaging(stage,ownedStage.device,ownedStage.inode);
                fs.applyMode(stage,0700);
                State state=read(archive,stage,inspected.getAppPackage(),inspected.getArchitecture(),inspected,control);
                String receipt=UUID.randomUUID().toString();
                writeReceipt(stage,receipt);
                state.createLinksAndModes();
                DesktopDistro old=state.metadata.getDesktop();
                DesktopDistro restored=new DesktopDistro(newDesktopId,old.getName(),old.getRunMode(),
                    newAlias,old.getDe(),old.getGlMode(),old.getResolution(),old.getRefreshRate());
                DesktopArchiveFileSystem.Stat identity=fs.lstat(stage);
                if (identity==null || identity.kind!=DesktopArchiveFileSystem.Kind.DIRECTORY
                        || identity.device!=ownedStage.device || identity.inode!=ownedStage.inode)
                    throw failure("archive_staging_identity_changed");
                requireAbsent(target);
                fs.rename(stage,target); published=true;
                return new DesktopArchiveImportResult(containersRoot,target,receipt,state.metadata,restored,identity);
            } catch (IOException | RuntimeException e) {
                if (!published) try {
                    DesktopArchiveFileSystem.Stat current=fs.lstat(stage);
                    if (current!=null) {
                        if (current.kind!=DesktopArchiveFileSystem.Kind.DIRECTORY
                                || current.device!=ownedStage.device || current.inode!=ownedStage.inode)
                            throw failure("archive_cleanup_identity_mismatch");
                        deleteTree(stage);
                    }
                } catch (IOException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
        }
    }
    public void discardImportedContainer(DesktopArchiveImportResult result) throws IOException {
        Objects.requireNonNull(result);
        File directory=result.getContainerDirectory();
        if (!directory.getParentFile().getCanonicalFile().equals(result.containersRoot.getCanonicalFile()))
            throw failure("archive_cleanup_identity_mismatch");
        requireDirectory(directory);
        DesktopArchiveFileSystem.Stat identity=fs.lstat(directory);
        if (identity.device!=result.device || identity.inode!=result.inode)
            throw failure("archive_cleanup_identity_mismatch");
        fs.applyMode(directory,0700);
        File receipt=new File(directory,RECEIPT);
        DesktopArchiveFileSystem.Stat stat=fs.lstat(receipt);
        if (stat==null || stat.kind!=DesktopArchiveFileSystem.Kind.FILE || stat.size>128)
            throw failure("archive_cleanup_identity_mismatch");
        byte[] bytes;
        try (InputStream input=fs.openRegular(receipt,stat)) { bytes=readSmall(input,stat.size,128); }
        if (!result.receipt.equals(new String(bytes,StandardCharsets.UTF_8)))
            throw failure("archive_cleanup_identity_mismatch");
        deleteTree(directory);
    }
    private State read(File archive, File stage, String expectedPackage, String expectedArch,
                       DesktopArchiveMetadata expected, DesktopArchiveControl control) throws IOException {
        Objects.requireNonNull(control);
        DesktopArchiveFileSystem.Stat source=fs.lstat(archive);
        if (source==null || source.kind!=DesktopArchiveFileSystem.Kind.FILE
                || source.size>limits.maxArchiveBytes) throw failure("invalid_archive_file");
        MessageDigest compressedHash=digest();
        Progress progress=new Progress(control,"desktop_archive_import");
        State state;
        try (InputStream file=fs.openRegular(archive,source);
             LimitInput limited=new LimitInput(file,limits.maxArchiveBytes,compressedHash);
             BufferedInputStream buffered=new BufferedInputStream(limited,32768);
             GzipCompressorInputStream gzip=new GzipCompressorInputStream(buffered,false);
             StrictDesktopTarInput framed=new StrictDesktopTarInput(gzip,limits);
             TarArchiveInputStream tar=new TarArchiveInputStream(framed,512,512,"UTF-8")) {
            TarArchiveEntry first=tar.getNextTarEntry();
            requireJson(first,"manifest.json");
            byte[] manifest=readSmall(tar,first.getSize(),limits.maxIndexBytes);
            DesktopArchiveMetadata metadata=DesktopArchiveMetadata.decode(manifest,expectedPackage,expectedArch);
            if (expected!=null && !Arrays.equals(expected.encode(),manifest))
                throw failure("archive_changed_after_inspection");
            state=new State(metadata,stage,progress);
            TarArchiveEntry entry;
            boolean complete=false;
            while ((entry=tar.getNextTarEntry())!=null) {
                if ("integrity.json".equals(entry.getName())) {
                    requireJson(entry,"integrity.json");
                    byte[] integrity=readSmall(tar,entry.getSize(),limits.maxIndexBytes);
                    if (!Arrays.equals(integrity,state.integrity(manifest))) throw failure("archive_integrity_mismatch");
                    complete=true;
                    if (tar.getNextTarEntry()!=null) throw failure("archive_trailing_entry");
                    break;
                }
                state.accept(entry,tar);
            }
            if (!complete) throw failure("archive_integrity_missing");
            byte[] drain=new byte[8192];
            while (framed.read(drain)!=-1) { /* Validate tar padding, gzip CRC and gzip trailer. */ }
            framed.requireComplete();
            if (buffered.read()!=-1) throw failure("archive_trailing_compressed_data");
            if (!source.sameFile(fs.lstat(archive))) throw failure("archive_source_changed");
            String checksum=hex(compressedHash.digest());
            if (expected!=null && !checksum.equals(expected.getArchiveSha256()))
                throw failure("archive_changed_after_inspection");
            state.validateContainer();
            state.metadata=metadata.inspected(checksum,state.bytes,state.count);
        }
        progress.finish(state.bytes);
        return state;
    }
    private void writeTree(TarArchiveOutputStream tar,File anchor,File file,String relative,
                           State state,Map<String,String> hardlinks) throws IOException {
        checkAncestors(anchor,file);
        DesktopArchiveFileSystem.Stat s=fs.lstat(file);
        if (s==null) throw failure("archive_source_changed");
        if (s.kind==DesktopArchiveFileSystem.Kind.SOCKET || s.kind==DesktopArchiveFileSystem.Kind.FIFO) return;
        if (s.kind==DesktopArchiveFileSystem.Kind.OTHER) throw failure("archive_unsupported_source_type");
        if (RECEIPT.equals(relative)) return;
        boolean placeholder=DesktopArchiveRuntimeExclusions.filePlaceholder(relative);
        if (placeholder && s.kind!=DesktopArchiveFileSystem.Kind.FILE)
            throw failure("archive_runtime_placeholder_invalid");
        String name=relative.isEmpty() ? ROOT : ROOT+"/"+relative;
        byte type=s.kind==DesktopArchiveFileSystem.Kind.DIRECTORY ? TarConstants.LF_DIR
            : s.kind==DesktopArchiveFileSystem.Kind.SYMLINK ? TarConstants.LF_SYMLINK : TarConstants.LF_NORMAL;
        String target="";
        boolean source=false;
        if (s.kind==DesktopArchiveFileSystem.Kind.SYMLINK) target=fs.readLink(file);
        if (s.kind==DesktopArchiveFileSystem.Kind.FILE && s.linkCount>1 && !placeholder) {
            String inode=s.device+":"+s.inode;
            if (hardlinks.containsKey(inode)) { type=TarConstants.LF_LINK; target=hardlinks.get(inode); }
            else {
                state.retain(128L+inode.length()*2L);
                hardlinks.put(inode,name); source=true;
            }
        }
        TarArchiveEntry entry=new TarArchiveEntry(name,type);
        entry.setMode(s.mode); entry.setUserId(0); entry.setGroupId(0);
        entry.setUserName(""); entry.setGroupName(""); entry.setModTime(s.modifiedMillis/1000*1000);
        entry.setSize(type==TarConstants.LF_NORMAL && !placeholder ? s.size : 0);
        if (!target.isEmpty()) entry.setLinkName(target);
        if (source) entry.addPaxHeader(LINK_SOURCE,"1");
        tar.putArchiveEntry(entry);
        if (type==TarConstants.LF_NORMAL) {
            if (placeholder) state.copyEntry(entry,new ByteArrayInputStream(new byte[0]),tar);
            else try (InputStream input=fs.openRegular(file,s)) { state.copyEntry(entry,input,tar); }
            if (!s.sameFile(fs.lstat(file))) throw failure("archive_source_changed");
        } else state.copyEntry(entry,new ByteArrayInputStream(new byte[0]),null);
        tar.closeArchiveEntry();
        if (type==TarConstants.LF_DIR && !DesktopArchiveRuntimeExclusions.root(relative,state.metadata.getAppPackage())) {
            File[] children=file.listFiles();
            if (children==null) throw failure("archive_source_unreadable");
            Arrays.sort(children,Comparator.comparing(File::getName));
            for (File child:children) writeTree(tar,anchor,child,
                relative.isEmpty()?child.getName():relative+"/"+child.getName(),state,hardlinks);
            if (!s.sameFile(fs.lstat(file))) throw failure("archive_source_changed");
        }
    }
    private final class State {
        DesktopArchiveMetadata metadata;
        final File stage;
        final Progress progress;
        final MessageDigest index=digest();
        final TreeOrder order=new TreeOrder();
        final List<Item> directories=new ArrayList<>(), links=new ArrayList<>();
        final Map<String,Item> hardlinkSources=new HashMap<>();
        final Map<String,String> osReleaseLinks=new HashMap<>();
        final Map<String,byte[]> osReleaseData=new HashMap<>();
        int count;
        long bytes;
        long retainedBytes;
        boolean marker,rootfs;
        final byte[] copyBuffer=new byte[32768];
        State(DesktopArchiveMetadata metadata,File stage,Progress progress) {
            this.metadata=metadata;this.stage=stage;this.progress=progress;
        }
        void accept(TarArchiveEntry entry,InputStream input) throws IOException {
            Item item=item(entry);
            if (stage!=null) {
                File out=destination(item.path);
                if (!item.path.equals(ROOT)) requireAbsent(out);
                if ("directory".equals(item.type)) {
                    if (!item.path.equals(ROOT) && !out.mkdir()) throw failure("archive_mkdir_failed");
                    fs.applyMode(out,0700);
                }
                if ("file".equals(item.type)) {
                    if (!out.createNewFile()) throw failure("archive_entry_exists");
                    fs.applyMode(out,0600);
                    try (OutputStream output=new BufferedOutputStream(new FileOutputStream(out))) {
                        consume(item,input,output);
                    }
                    fs.applyModifiedTime(out,item.mtime); fs.applyMode(out,item.mode);
                    return;
                }
            }
            consume(item,input,null);
        }
        void copyEntry(TarArchiveEntry entry,InputStream input,OutputStream output) throws IOException {
            Item item=item(entry); consume(item,input,output);
            if ("file".equals(item.type) && input.read()!=-1) throw failure("archive_source_changed");
        }
        Item item(TarArchiveEntry e) throws IOException {
            String path=path(e.getName());
            if (!path.equals(ROOT) && !path.startsWith(ROOT+"/")) throw failure("archive_payload_path");
            String relative=path.equals(ROOT)?"":path.substring(ROOT.length()+1);
            if (RECEIPT.equals(relative) || DesktopArchiveRuntimeExclusions.content(relative,metadata.getAppPackage()))
                throw failure("archive_excluded_path");
            String type=e.isDirectory()?"directory":e.isSymbolicLink()?"symlink":e.isLink()?"hardlink":e.isFile()?"file":null;
            if (type==null || e.isSparse()) throw failure("archive_unsupported_entry");
            if (DesktopArchiveRuntimeExclusions.filePlaceholder(relative)
                    && (!"file".equals(type) || e.getSize()!=0 || e.getExtraPaxHeader(LINK_SOURCE)!=null))
                throw failure("archive_runtime_placeholder_invalid");
            order.add(path,"directory".equals(type));
            if (count>=limits.maxEntries) throw failure("archive_entry_limit"); count++;
            int mode=e.getMode();
            if (mode<0 || (mode & ~07777)!=0 || e.getSize()<0) throw failure("archive_invalid_entry_metadata");
            if (!"file".equals(type) && e.getSize()!=0) throw failure("archive_invalid_entry_size");
            if (e.getModTime().getTime()<0) throw failure("archive_invalid_entry_time");
            String link="symlink".equals(type)||"hardlink".equals(type)?e.getLinkName():"";
            if (!link.isEmpty() && (link.indexOf('\0')>=0 || link.getBytes(StandardCharsets.UTF_8).length>4096))
                throw failure("archive_invalid_link");
            if (("symlink".equals(type)||"hardlink".equals(type)) && link.isEmpty()) throw failure("archive_invalid_link");
            if ("hardlink".equals(type)) {
                link=path(link);
                Item target=hardlinkSources.get(link);
                if (target==null || target.mode!=mode || target.mtime!=e.getModTime().getTime())
                    throw failure("archive_invalid_hardlink");
            }
            if ("symlink".equals(type) && !relative.startsWith("rootfs/")) {
                if (link.startsWith("/") || escapes(relative,link)) throw failure("archive_metadata_link_escape");
            }
            String flag=e.getExtraPaxHeader(LINK_SOURCE);
            if (flag!=null && (!"1".equals(flag)||!"file".equals(type))) throw failure("archive_invalid_hardlink_source");
            Item item=new Item(path,type,mode,e.getSize(),e.getModTime().getTime(),link,flag!=null);
            long next=checkedAdd(bytes,item.size);
            if (checkedAdd(next,checkedMultiply(count,4096))>limits.maxExpandedBytes) throw failure("archive_byte_limit");
            if (stage!=null && ("directory".equals(type)||"symlink".equals(type)||"hardlink".equals(type))) {
                retain(item.retainedCost());
                if ("directory".equals(type)) directories.add(item); else links.add(item);
            }
            if ("rootfs".equals(relative)) rootfs="directory".equals(type);
            if ((".desktop-de-"+metadata.getDesktop().getDe().getStorageValue()).equals(relative)) marker="file".equals(type);
            if (relative.equals("rootfs/etc/os-release") && "symlink".equals(type)) osReleaseLinks.put(relative,link);
            return item;
        }
        void consume(Item item,InputStream input,OutputStream output) throws IOException {
            MessageDigest content=digest();
            boolean capture=item.path.equals(ROOT+"/rootfs/etc/os-release")||item.path.equals(ROOT+"/rootfs/usr/lib/os-release");
            ByteArrayOutputStream small=capture&&"file".equals(item.type)?new ByteArrayOutputStream():null;
            if (small!=null && item.size>65536) throw failure("archive_os_release_limit");
            byte[] buffer=copyBuffer;long remaining=item.size;
            while (remaining>0) {
                int n=input.read(buffer,0,(int)Math.min(remaining,buffer.length));
                if (n<0) throw failure("archive_truncated_entry");
                if (n==0) continue;
                if (output!=null) output.write(buffer,0,n);
                if (small!=null) small.write(buffer,0,n);
                content.update(buffer,0,n);remaining-=n;bytes=checkedAdd(bytes,n);progress.tick(bytes);
            }
            if (small!=null) osReleaseData.put(item.path.substring(ROOT.length()+1),small.toByteArray());
            String checksum="file".equals(item.type)?hex(content.digest()):"";
            index.update(item.record(checksum));
            if (item.linkSource) {
                retain(item.retainedCost()+96);
                hardlinkSources.put(item.path,item);
            }
            progress.tick(bytes);
        }
        void retain(long cost) throws IOException {
            if (cost<0 || cost>RETAINED_METADATA_LIMIT-retainedBytes)
                throw failure("archive_retained_metadata_limit");
            retainedBytes+=cost;
        }
        byte[] integrity(byte[] manifest) throws IOException {
            String value="{\"entryCount\":"+count+",\"regularBytes\":"+bytes
                +",\"manifestSha256\":\""+hex(digest().digest(manifest))+"\",\"entriesSha256\":\""
                +hex(index.digest())+"\"}";
            byte[] data=value.getBytes(StandardCharsets.UTF_8);
            if (data.length>limits.maxIndexBytes) throw failure("archive_metadata_limit");
            return data;
        }
        void validateContainer() throws IOException {
            if (!rootfs || !marker) throw failure("archive_container_not_installed");
            byte[] release=osReleaseData.get("rootfs/etc/os-release");
            if (release==null && osReleaseLinks.containsKey("rootfs/etc/os-release")) {
                String target=osReleaseLinks.get("rootfs/etc/os-release");
                String resolved=resolveGuest("/etc/os-release",target);
                if ("/usr/lib/os-release".equals(resolved)) release=osReleaseData.get("rootfs/usr/lib/os-release");
            }
            if (release==null) throw failure("archive_os_release_missing");
            Map<String,String> fields=new HashMap<>();
            for (String line:new String(release,StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("ID=")||line.startsWith("VERSION_CODENAME=")) {
                    int eq=line.indexOf('=');String value=line.substring(eq+1).trim();
                    if (value.length()>=2 && ((value.startsWith("\"")&&value.endsWith("\""))
                            ||(value.startsWith("'")&&value.endsWith("'")))) value=value.substring(1,value.length()-1);
                    if (fields.put(line.substring(0,eq),value)!=null) throw failure("archive_os_release_invalid");
                }
            }
            if (!"debian".equals(fields.get("ID")) || !metadata.getCodename().equals(fields.get("VERSION_CODENAME")))
                throw failure("archive_distribution_mismatch");
        }
        File destination(String name) throws IOException {
            if (name.equals(ROOT)) return stage;
            File file=new File(stage,name.substring(ROOT.length()+1)); checkAncestors(stage,file); return file;
        }
        void createLinksAndModes() throws IOException {
            for (Item item:links) {
                progress.tick(bytes);
                File link=destination(item.path);requireAbsent(link);
                if ("symlink".equals(item.type)) fs.createSymbolicLink(item.link,link);
                else {
                    File target=destination(item.link);
                    DesktopArchiveFileSystem.Stat s=fs.lstat(target);
                    if (s==null || s.kind!=DesktopArchiveFileSystem.Kind.FILE) throw failure("archive_invalid_hardlink");
                    fs.createHardLink(target,link);
                }
            }
            for (int i=directories.size()-1;i>=0;i--) {
                progress.tick(bytes);
                Item item=directories.get(i);File file=destination(item.path);
                fs.applyModifiedTime(file,item.mtime);fs.applyMode(file,item.mode);
            }
        }
    }
    private static final class Item {
        final String path,type,link;final int mode;final long size,mtime;final boolean linkSource;
        Item(String path,String type,int mode,long size,long mtime,String link,boolean linkSource) {
            this.path=path;this.type=type;this.mode=mode;this.size=size;this.mtime=mtime;this.link=link;this.linkSource=linkSource;
        }
        byte[] record(String checksum) {
            return ("{\"path\":"+DesktopArchiveMetadata.quote(path)+",\"type\":"+DesktopArchiveMetadata.quote(type)
                +",\"mode\":"+mode+",\"size\":"+size+",\"mtime\":"+mtime+",\"link\":"+DesktopArchiveMetadata.quote(link)
                +",\"hardlinkSource\":"+linkSource+",\"sha256\":"+DesktopArchiveMetadata.quote(checksum)+"}\n").getBytes(StandardCharsets.UTF_8);
        }
        long retainedCost() { return 256L+(path.length()+link.length())*2L; }
    }
    private static final class TreeOrder {
        final List<String> parents=new ArrayList<>(),lastChild=new ArrayList<>();
        void add(String name,boolean directory) throws IOException {
            if (parents.isEmpty()) {
                if (!name.equals(ROOT)||!directory) throw failure("archive_payload_root_missing");
                parents.add(ROOT);lastChild.add("");return;
            }
            int slash=name.lastIndexOf('/');String parent=name.substring(0,slash),child=name.substring(slash+1);
            while (!parents.isEmpty()&&!parents.get(parents.size()-1).equals(parent)) {
                parents.remove(parents.size()-1);lastChild.remove(lastChild.size()-1);
            }
            if (parents.isEmpty()) throw failure("archive_parent_or_order_invalid");
            int i=lastChild.size()-1;
            if (child.compareTo(lastChild.get(i))<=0) throw failure("archive_duplicate_or_order_invalid");
            lastChild.set(i,child);
            if (directory) {
                if (parents.size()>=256) throw failure("archive_depth_limit");
                parents.add(name);lastChild.add("");
            }
        }
    }
    private void putJson(TarArchiveOutputStream tar,String name,byte[] bytes) throws IOException {
        TarArchiveEntry entry=new TarArchiveEntry(name);entry.setSize(bytes.length);entry.setMode(0600);
        entry.setUserId(0);entry.setGroupId(0);entry.setModTime(0);
        tar.putArchiveEntry(entry);tar.write(bytes);tar.closeArchiveEntry();
    }
    private void requireJson(TarArchiveEntry e,String name) throws IOException {
        if (e==null || !name.equals(e.getName()) || !e.isFile() || e.isSparse()
                || e.getSize()<1 || e.getSize()>limits.maxIndexBytes) throw failure("archive_control_entry_invalid");
    }
    private static byte[] readSmall(InputStream in,long length,int limit) throws IOException {
        if (length<0||length>limit) throw failure("archive_metadata_limit");
        byte[] b=new byte[(int)length];int n=0;
        while (n<b.length) { int got=in.read(b,n,b.length-n);if (got<0) throw failure("archive_truncated_entry");n+=got; }
        return b;
    }
    static String path(String value) throws IOException {
        if (value==null || value.isEmpty() || value.startsWith("/")
                || value.indexOf('\0')>=0 || value.getBytes(StandardCharsets.UTF_8).length>4096) throw failure("archive_invalid_path");
        String s=value.endsWith("/")?value.substring(0,value.length()-1):value;
        for (String part:s.split("/",-1)) if (part.isEmpty()||part.equals(".")||part.equals("..")) throw failure("archive_invalid_path");
        return s;
    }
    private static boolean escapes(String path,String link) {
        String base=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";
        int depth=0;
        for (String part:(base+"/"+link).split("/")) {
            if (part.equals("..")) { if (--depth<0) return true; }
            else if (!part.isEmpty()&&!part.equals(".")) depth++;
        }
        return false;
    }
    private static String resolveGuest(String path,String link) throws IOException {
        List<String> parts=new ArrayList<>();
        String full=link.startsWith("/")?link:path.substring(0,path.lastIndexOf('/')+1)+link;
        for (String s:full.split("/")) {
            if (s.isEmpty()||s.equals(".")) continue;
            if (s.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size()-1); }
            else parts.add(s);
        }
        return "/"+String.join("/",parts);
    }
    private void checkAncestors(File root,File file) throws IOException {
        File current=file.getAbsoluteFile().getParentFile(), anchor=root.getAbsoluteFile();
        if (file.getAbsoluteFile().equals(anchor)) return;
        while (current!=null) {
            requireDirectory(current);
            if (current.equals(anchor)) return;
            current=current.getParentFile();
        }
        throw failure("archive_path_escape");
    }
    private void requireDirectory(File file) throws IOException {
        DesktopArchiveFileSystem.Stat s=fs.lstat(file);
        if (s==null || s.kind!=DesktopArchiveFileSystem.Kind.DIRECTORY) throw failure("archive_directory_required");
    }
    private void requireAbsent(File file) throws IOException {
        if (fs.lstat(file)!=null) throw failure("archive_destination_exists");
    }
    private void checkLockFile(File file) throws IOException {
        DesktopArchiveFileSystem.Stat s=fs.lstat(file);
        if (s!=null&&s.kind!=DesktopArchiveFileSystem.Kind.FILE) throw failure("archive_invalid_lock");
    }
    private void writeReceipt(File directory,String receipt) throws IOException {
        File f=new File(directory,RECEIPT);requireAbsent(f);
        try (FileOutputStream out=new FileOutputStream(f)) {
            out.write(receipt.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
        fs.applyMode(f,0600);
    }
    private void deleteTree(File path) throws IOException {
        DesktopArchiveFileSystem.Stat stat=fs.lstat(path);
        if (stat==null) return;
        if (stat.kind==DesktopArchiveFileSystem.Kind.DIRECTORY) {
            fs.applyMode(path,0700);
            File[] files=path.listFiles();if (files==null) throw failure("archive_cleanup_unreadable");
            for (File file:files) deleteTree(file);
        }
        if (!path.delete()) throw failure("archive_cleanup_failed");
    }
    private static void validateAlias(String alias) throws IOException {
        if (alias==null||!alias.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw failure("archive_invalid_alias");
    }
    private static File staging(File root,String alias) { return new File(root,".desktop-import-"+alias); }
    static DesktopArchiveException failure(String code) { return new DesktopArchiveException(code); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] data) {
        char[] out=new char[data.length*2],digits="0123456789abcdef".toCharArray();
        for(int i=0;i<data.length;i++){out[i*2]=digits[(data[i]&255)>>>4];out[i*2+1]=digits[data[i]&15];}return new String(out);
    }
    private static long checkedAdd(long a,long b) throws IOException {
        try{return Math.addExact(a,b);}catch(ArithmeticException e){throw failure("archive_size_overflow");}
    }
    private static long checkedMultiply(long a,long b) throws IOException {
        try{return Math.multiplyExact(a,b);}catch(ArithmeticException e){throw failure("archive_size_overflow");}
    }
    private static final class Progress {
        final DesktopArchiveControl control;final String message;long previous=-1048576,lastTime;
        Progress(DesktopArchiveControl control,String message){this.control=control;this.message=message;}
        void tick(long bytes)throws IOException {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("archive_cancelled");
            long now=System.nanoTime();if(bytes-previous>=1048576||now-lastTime>=250000000L){control.update(message,bytes,-1);previous=bytes;lastTime=now;}
        }
        void finish(long bytes)throws IOException{control.update(message,bytes,bytes);}
    }
    private static final class LimitInput extends FilterInputStream {
        final long limit;final MessageDigest digest;long count;
        LimitInput(InputStream in,long limit,MessageDigest digest){super(in);this.limit=limit;this.digest=digest;}
        @Override public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int o,int l)throws IOException{int n=in.read(b,o,l);if(n>0){if(n>limit-count)throw failure("archive_compressed_limit");count+=n;digest.update(b,o,n);}return n;}
    }
    private static final class LimitOutput extends FilterOutputStream {
        final long limit;long count;LimitOutput(OutputStream out,long limit){super(out);this.limit=limit;}
        @Override public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
        @Override public void write(byte[] b,int o,int l)throws IOException{if(l>limit-count)throw failure("archive_compressed_limit");out.write(b,o,l);count+=l;}
    }
}

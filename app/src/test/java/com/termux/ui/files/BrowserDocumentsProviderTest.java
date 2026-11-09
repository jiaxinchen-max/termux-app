package com.termux.ui.files;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.termux.ui.data.GameStoragePaths;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=28)
public class BrowserDocumentsProviderTest {
    private Context context;private File directory,file;private BrowserDocumentsProvider provider;
    @Before public void setup() throws Exception {
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton()).addExtensionMimeTypMapping("txt", "text/plain");
        context=RuntimeEnvironment.getApplication();directory=new File(context.getFilesDir(),"share %2F # 文件");assertTrue(directory.mkdir());
        file=new File(directory,"readme.TXT");Files.write(file.toPath(),"hello".getBytes("UTF-8"));
        provider=Robolectric.buildContentProvider(BrowserDocumentsProvider.class).create().get();
    }
    @Test public void fileIntentCarriesMimeCanonicalUriAndOnlyReadPermission() throws Exception {
        Intent intent=BrowserDocumentsProvider.openIntent(context,file);
        assertEquals(Intent.ACTION_VIEW,intent.getAction());assertEquals("text/plain",intent.getType());
        assertEquals(file.getCanonicalPath(),DocumentsContract.getDocumentId(intent.getData()));
        assertEquals(intent.getData(),intent.getClipData().getItemAt(0).getUri());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.getFlags());
    }
    @Test public void directoryGrantsOnlyItsTreeAndUnknownFileHasBinaryMime() throws Exception {
        Intent intent=BrowserDocumentsProvider.openIntent(context,directory);
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR,intent.getType());
        assertEquals(directory.getCanonicalPath(),DocumentsContract.getTreeDocumentId(intent.getClipData().getItemAt(0).getUri()));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,intent.getFlags());
        File unknown=new File(directory,"unknown.blahxyz");assertTrue(unknown.createNewFile());
        assertEquals("application/octet-stream",BrowserDocumentsProvider.openIntent(context,unknown).getType());
    }
    @Test public void metadataContentAndChildrenAreReadableButNoWritesAreAdvertised() throws Exception {
        try(Cursor cursor=provider.queryDocument(file.getCanonicalPath(),null)) {
            assertTrue(cursor.moveToFirst());assertEquals("readme.TXT",cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)));
            assertEquals("text/plain",cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)));
            assertEquals(5,cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)));
            assertEquals(0,cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)));
        }
        try(java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(provider.openDocument(file.getCanonicalPath(),"r",null))) {
            assertEquals('h',input.read());
        }
        try(Cursor cursor=provider.queryChildDocuments(directory.getCanonicalPath(),null,(String)null)) { assertEquals(1,cursor.getCount()); }
        try(Cursor cursor=provider.queryRoots(null)) { assertEquals(0,cursor.getCount()); }
    }
    @Test public void writeModesAndOutsideOrNoncanonicalIdsAreRejected() throws Exception {
        for(String mode:new String[]{"w","rw","wa","rwt"}) {
            try { provider.openDocument(file.getCanonicalPath(),mode,null);fail(mode); }catch(FileNotFoundException expected) {}
        }
        for(String id:new String[]{context.getCacheDir().getCanonicalPath(),directory.getCanonicalPath()+"/../"+directory.getName(),"relative",file.getPath()+"/../readme.TXT"}) {
            try { provider.queryDocument(id,null);fail(id); }catch(FileNotFoundException expected) {}
        }
    }
    @Test public void rootsCannotBeSharedAndEscapingTreeLinksAreNotChildren() throws Exception {
        for(File root:new File[]{context.getFilesDir(),new GameStoragePaths(context.getFilesDir()).getImportedGamesDirectory(),android.os.Environment.getExternalStorageDirectory()}) {
            root.mkdirs();try {BrowserDocumentsProvider.openIntent(context,root);fail(root.toString());}catch(java.io.IOException expected) {}
        }
        File other=new File(context.getFilesDir(),"other.txt");assertTrue(other.createNewFile());
        assertFalse(provider.isChildDocument(directory.getCanonicalPath(),other.getCanonicalPath()));
        File link=new File(directory,"link");Files.createSymbolicLink(link.toPath(),other.toPath());
        assertFalse(provider.isChildDocument(directory.getCanonicalPath(),link.getPath()));
        try(Cursor cursor=provider.queryChildDocuments(directory.getCanonicalPath(),null,(String)null)){assertEquals(1,cursor.getCount());}
        try {provider.queryDocument(link.getPath(),null);fail();}catch(FileNotFoundException expected) {}
    }
    @Test public void replacingGrantedFileWithLinkCannotRedirectGrant() throws Exception {
        String id=DocumentsContract.getDocumentId(BrowserDocumentsProvider.openIntent(context,file).getData());
        File other=new File(context.getFilesDir(),"secret.txt");Files.write(other.toPath(),new byte[]{1});
        assertTrue(file.delete());Files.createSymbolicLink(file.toPath(),other.toPath());
        try {provider.openDocument(id,"r",null);fail();}catch(FileNotFoundException expected) {}
    }
    @Test public void manifestRequiresSystemDocumentsPermissionAndSupportsTemporaryGrants() {
        ProviderInfo info=context.getPackageManager().resolveContentProvider(BrowserDocumentsProvider.authority(context),0);
        assertNotNull(info);assertTrue(info.exported);assertTrue(info.grantUriPermissions);
        assertEquals("android.permission.MANAGE_DOCUMENTS",info.readPermission);
        assertEquals("android.permission.MANAGE_DOCUMENTS",info.writePermission);
    }
    @Test public void treeQueryRejectsAValidBrowserFileOutsideTheGrantedSubtree() throws Exception {
        Uri tree = BrowserDocumentsProvider.openIntent(context, directory).getClipData().getItemAt(0).getUri();
        Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, file.getCanonicalPath());
        try (Cursor cursor = provider.query(child, null, (android.os.Bundle)null, null)) { assertEquals(1, cursor.getCount()); }
        File other = new File(context.getFilesDir(), "outside-tree.txt"); assertTrue(other.createNewFile());
        Uri forged = DocumentsContract.buildDocumentUriUsingTree(tree, other.getCanonicalPath());
        try { provider.query(forged, null, (android.os.Bundle)null, null); fail("Escaped granted tree"); }
        catch (SecurityException expected) {}
    }

}

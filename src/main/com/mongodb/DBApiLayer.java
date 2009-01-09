// DBApiLayer.java

package com.mongodb;

import java.io.*;
import java.nio.*;
import java.util.*;

import com.mongodb.io.*;
import com.mongodb.util.*;

/**
 * @expose
 * @docmodule system.database.db
 * */
public abstract class DBApiLayer extends DBBase {

    /** @unexpose */
    static final boolean D = Boolean.getBoolean( "DEBUG.DB" );
    /** The maximum number of cursors allowed */
    static final int NUM_CURSORS_BEFORE_KILL = 100;
    
    static final boolean SHOW = Boolean.getBoolean( "DB.SHOW" );

    /** Initializes a new API layer for a database with a given name.
     * @param root name of the database
     */
    protected DBApiLayer( String root ){
        super( root );

        _root = root;
    }

    /**
     * @param buf
     */
    protected abstract void doInsert( ByteBuffer buf );
    /**
     * @param buf
     */
    protected abstract void doDelete( ByteBuffer buf );
    /**
     * @param buf
     */
    protected abstract void doUpdate( ByteBuffer buf );
    /**
     * @param buf
     */
    protected abstract void doKillCursors( ByteBuffer buf );

    /**
     * @param out
     * @param in
     */
    protected abstract int doQuery( ByteBuffer out , ByteBuffer in );
    /**
     * @param out
     * @param in
     */
    protected abstract int doGetMore( ByteBuffer out , ByteBuffer in );

    public abstract String debugString();

    /**
     * @param name the name of the collection to find
     */
    protected MyCollection doGetCollection( String name ){
        MyCollection c = _collections.get( name );
        if ( c != null )
            return c;

        synchronized ( _collections ){
            c = _collections.get( name );
            if ( c != null )
                return c;

            c = new MyCollection( name );
            _collections.put( name , c );
        }

        return c;
    }

    /** @unexpose */
    String _removeRoot( String ns ){
        if ( ! ns.startsWith( _root + "." ) )
            return ns;
        return ns.substring( _root.length() + 1 );
    }

    public MyCollection getCollectionFromFull( String fullNameSpace ){
        // TOOD security

        if ( fullNameSpace.indexOf( "." ) < 0 ) {
            // assuming local
            return doGetCollection( fullNameSpace );
        }

        final int idx = fullNameSpace.indexOf( "." );

        final String root = fullNameSpace.substring( 0 , idx );
        final String table = fullNameSpace.substring( idx + 1 );
        
        if ( _root.equals( root ) )
            return doGetCollection( table );
        
        throw new RuntimeException( "can't get sister dbs yet" );
    }

    public Set<String> getCollectionNames(){
        
        DBCollection namespaces = getCollection( "system.namespaces" );
        if ( namespaces == null )
            throw new RuntimeException( "this is impossible" );

	Iterator<DBObject> i = namespaces.find( new BasicDBObject() , null , 0 , 0 );
	if ( i == null )
	    return new HashSet<String>();

        List<String> tables = new ArrayList<String>();

        for (  ; i.hasNext() ;  ){
            DBObject o = i.next();
            String n = o.get( "name" ).toString();
            int idx = n.indexOf( "." );

            String root = n.substring( 0 , idx );
            if ( ! root.equals( _root ) )
                continue;

	    if ( n.indexOf( "$" ) >= 0 )
		continue;

            String table = n.substring( idx + 1 );

            tables.add( table );
        }


        Collections.sort( tables );

        return new OrderedSet<String>( tables );
    }

    public static Collection<String> getRootNamespaces( String ip ){
        throw new RuntimeException( "not implemented yet" );
    }

    class MyCollection extends DBCollection {
        MyCollection( String name ){
            super( DBApiLayer.this , name );
            _fullNameSpace = _root + "." + name;
        }

        public void doapply( DBObject o ){
            o.put( "_ns" , _removeRoot( _fullNameSpace ) );
        }
        
        public DBObject dofind( ObjectId id ){
            DBObject lookup = new BasicDBObject();
            lookup.put( "_id" , id );

            Iterator<DBObject> res = find( lookup );
            if ( res == null )
                return null;

            DBObject o = res.next();
            
            if ( res.hasNext() )
		System.out.println( "multiple entries with same _id.  collection:" + _fullNameSpace + " id:" + id );

            return o;
        }

        public DBObject doSave( DBObject o ){
            return save( o , true );
        }

        public DBObject save( DBObject o , boolean shouldApply ){

            if ( SHOW ) System.out.println( "save:  " + _fullNameSpace + " " + JSON.serialize( o ) );

            if ( shouldApply ){
                apply( o );
                ((ObjectId)o.get( "_id" ) )._new = false;
            }

            ByteEncoder encoder = ByteEncoder.get();

            encoder._buf.putInt( 0 ); // reserved
            encoder._put( _fullNameSpace );

            encoder.putObject( o );
            encoder.flip();
            
            try {
                doInsert( encoder._buf );
            }
            finally {
                encoder.done();
            }

            return o;
        }

        public int remove( DBObject o ){

            if ( SHOW ) System.out.println( "remove: " + _fullNameSpace + " " + JSON.serialize( o ) );

            ByteEncoder encoder = ByteEncoder.get();
            encoder._buf.putInt( 0 ); // reserved
            encoder._put( _fullNameSpace );

            Collection<String> keys = o.keySet();

            if ( keys.size() == 1 &&
                 keys.iterator().next().equals( "_id" ) &&
                 o.get( keys.iterator().next() ) instanceof ObjectId )
                encoder._buf.putInt( 1 );
            else
                encoder._buf.putInt( 0 );

            encoder.putObject( o );
            encoder.flip();
            
            try {
                doDelete( encoder._buf );
            }
            finally {
                encoder.done();
            }

            return -1;
        }

        void _cleanCursors(){
            if ( _deadCursorIds.size() == 0 )
                return;

            if ( _deadCursorIds.size() % 20 != 0 && _deadCursorIds.size() < NUM_CURSORS_BEFORE_KILL )
                return;

            List<Long> l = _deadCursorIds;
            _deadCursorIds = new Vector<Long>();

            System.out.println( "trying to kill cursors : " + l.size() );

            try {
                killCursors( l );
            }
            catch ( Throwable t ){
                t.printStackTrace();
                _deadCursorIds.addAll( l );
            }
        }

        void killCursors( List<Long> all ){
            if ( all == null || all.size() == 0 )
                return;

            ByteEncoder encoder = ByteEncoder.get();
            encoder._buf.putInt( 0 ); // reserved

            encoder._buf.putInt( all.size() );
            for ( int i=0; i<all.size(); i++ )
                encoder._buf.putLong( all.get( i  ) );
            
            try {
                doKillCursors( encoder._buf );
            }
            finally {
                encoder.done();
            }
            
        }

        public Iterator<DBObject> find( DBObject ref , DBObject fields , int numToSkip , int numToReturn ){

            if ( SHOW ) System.out.println( "find: " + _fullNameSpace + " " + JSON.serialize( ref ) );

            _cleanCursors();

            ByteEncoder encoder = ByteEncoder.get();

            encoder._buf.putInt( 0 ); // options
            encoder._put( _fullNameSpace );

            encoder._buf.putInt( numToSkip );
            encoder._buf.putInt( numToReturn );
            encoder.putObject( ref ); // ref
            if ( fields != null )
                encoder.putObject( fields ); // fields to return
            encoder.flip();

            ByteDecoder decoder = ByteDecoder.get( DBApiLayer.this , this );

            try {
                int len = doQuery( encoder._buf , decoder._buf );
                decoder.doneReading( len );
                
                SingleResult res = new SingleResult( _fullNameSpace , decoder , null );
                
                if ( res._lst.size() == 0 )
                    return null;
                
                if ( res._lst.size() == 1 ){
                    Object err = res._lst.get(0).get( "$err" );
                    if ( err != null )
                        throw new RuntimeException( "db error [" + err + "]" );
                }
                
                return new Result( this , res , numToReturn );
            }
            finally {
                decoder.done();
                encoder.done();
            }
        }
        
        public DBObject update( DBObject query , DBObject o , boolean upsert , boolean apply ){

            if ( SHOW ) System.out.println( "update: " + _fullNameSpace + " " + JSON.serialize( query ) );

            if ( apply ){
                apply( o );
                ObjectId id = ((ObjectId)o.get( "_id" ));
                id._new = false;
            }
            
            ByteEncoder encoder = ByteEncoder.get();
            encoder._buf.putInt( 0 ); // reserved
            encoder._put( _fullNameSpace );

            encoder._buf.putInt( upsert ? 1 : 0 );

            encoder.putObject( query );
            encoder.putObject( o );

            encoder.flip();

            try {
                doUpdate( encoder._buf );
            }
            finally {
                encoder.done();
            }

            return o;
        }

        public void ensureIndex( DBObject keys , String name ){
            DBObject o = new BasicDBObject();
            o.put( "name" , name );
            o.put( "ns" , _fullNameSpace );
            o.put( "key" , keys );

	    //dm-system isnow in our database
	    DBApiLayer.this.doGetCollection( "system.indexes" ).save( o , false );
        }

        final String _fullNameSpace;
    }

    class QueryHeader {
        
        QueryHeader( ByteBuffer buf ){
            this( buf , buf.position() );
        }

        QueryHeader( ByteBuffer buf , int start ){
            _reserved = buf.getInt( start );
            _cursor = buf.getLong( start + 4 );
            _startingFrom = buf.getInt( start + 12 );
            _num = buf.getInt( 16 );
        }

        int headerSize(){
            return 20;
        }

        void skipPastHeader( ByteBuffer buf ){
            buf.position( buf.position() + headerSize() );
        }

        final int _reserved;
        final long _cursor;
        final int _startingFrom;
        final int _num;
    }

    class SingleResult extends QueryHeader {
        
        SingleResult( String fullNameSpace , ByteDecoder decoder , Set<ObjectId> seen ){
            super( decoder._buf );

            _bytes = decoder.remaining();
            _fullNameSpace = fullNameSpace;
            skipPastHeader( decoder._buf );
            
            if ( _num == 0 )
                _lst = EMPTY;
            else if ( _num < 3 )
                _lst = new LinkedList<DBObject>();
            else
                _lst = new ArrayList<DBObject>( _num );

            if ( _num > 0 ){
                int num = 0;

                while( decoder.more() && num < _num ){
                    final DBObject o = decoder.readObject();

                    if ( seen != null ){
                        ObjectId id = (ObjectId)o.get( "_id" );
                        if ( id != null ){
                            if ( seen.contains( id ) ) continue;
                            seen.add( id );
                        }
                    }

                    o.put( "_ns" , _removeRoot( _fullNameSpace ) );
                    _lst.add( o );
                    num++;

                    if ( D ) {
                        System.out.println( "-- : " + o.keySet().size() );
                        for ( String s : o.keySet() )
                            System.out.println( "\t " + s + " : " + o.get( s ) );
                    }
                }
            }
        }

        boolean hasGetMore(){
            return _num > 0 && _cursor > 0;
        }

        public String toString(){
            return "reserved:" + _reserved + " _cursor:" + _cursor + " _startingFrom:" + _startingFrom + " _num:" + _num ;
        }

        final long _bytes;
        final String _fullNameSpace;

        final List<DBObject> _lst;
    }

    class Result implements Iterator<DBObject> {

        Result( MyCollection coll , SingleResult res , int numToReturn ){
            init( res );
            _collection = coll;
            _numToReturn = numToReturn;
        }

        private void init( SingleResult res ){
            _totalBytes += res._bytes;

            _curResult = res;
            for ( DBObject o : res._lst ){
                ObjectId id = (ObjectId)o.get( "_id" );
                if ( id != null )
                    _seen.add( id );
            }
            _cur = res._lst.iterator();
        }

        public DBObject next(){
            if ( _cur.hasNext() )
                return _cur.next();

            if ( ! _curResult.hasGetMore() )
		throw new RuntimeException( "no more" );

	    _advance();
	    return next();
        }

        public boolean hasNext(){
            if ( _cur.hasNext() )
                return true;

            if ( ! _curResult.hasGetMore() )
		return false;

	    _advance();
	    return hasNext();
        }

	private void _advance(){

	    if ( _curResult._cursor <= 0 )
		throw new RuntimeException( "can't advance a cursor <= 0" );

	    ByteEncoder encoder = ByteEncoder.get();

	    encoder._buf.putInt( 0 ); // reserved
	    encoder._put( _curResult._fullNameSpace );
	    encoder._buf.putInt( _numToReturn ); // num to return
	    encoder._buf.putLong( _curResult._cursor );
	    encoder.flip();

	    ByteDecoder decoder = ByteDecoder.get( DBApiLayer.this , _collection );

            try {
                int len = doGetMore( encoder._buf , decoder._buf );
                decoder.doneReading( len );
                
                SingleResult res = new SingleResult( _curResult._fullNameSpace , decoder , _seen );
                init( res );
            }
            finally {
                decoder.done();
                encoder.done();
            }

	}

        public void remove(){
            throw new RuntimeException( "can't remove this way" );
        }

        public String toString(){
            return "DBCursor";
        }

        protected void finalize(){
            if ( _curResult != null && _curResult._cursor > 0 )
                _deadCursorIds.add( _curResult._cursor );
        }

        public long totalBytes(){
            return _totalBytes;
        }

        SingleResult _curResult;
        Iterator<DBObject> _cur;
        final Set<ObjectId> _seen = new HashSet<ObjectId>();
        final MyCollection _collection;
        final int _numToReturn;

        private long _totalBytes = 0;
    }

    /** @unexpose */
    final String _root;
    final Map<String,MyCollection> _collections = Collections.synchronizedMap( new HashMap<String,MyCollection>() );
    final Map<String,DBApiLayer> _sisters = Collections.synchronizedMap( new HashMap<String,DBApiLayer>() );
    List<Long> _deadCursorIds = new Vector<Long>();

    static final List<DBObject> EMPTY = Collections.unmodifiableList( new LinkedList<DBObject>() );


}
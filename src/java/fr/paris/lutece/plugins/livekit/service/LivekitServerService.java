package fr.paris.lutece.plugins.livekit.service;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import fr.paris.lutece.plugins.appointment.modules.virtualmeeting.provider.IVirtualMeetingProvider;
import fr.paris.lutece.portal.service.util.AppLogService;
import fr.paris.lutece.portal.service.util.AppPropertiesService;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomAdmin;
import io.livekit.server.RoomCreate;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomList;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Service providing LiveKit server operations: token generation, room management, participant management. Implements {@link IVirtualMeetingProvider} so it is
 * automatically detected by {@code SpringContextService.getBeansOfType(IVirtualMeetingProvider.class)}.
 */
public class LivekitServerService implements IVirtualMeetingProvider
{
    private static final String PROPERTY_API_KEY = "livekit.server.apiKey";
    private static final String PROPERTY_API_SECRET = "livekit.server.apiSecret";
    private static final String PROPERTY_SERVER_URL = "livekit.server.url";
    private static final String PROPERTY_TOKEN_TTL = "livekit.server.token.ttl";
    private static final String PROPERTY_MEETING_URL_PATTERN = "livekit.server.meetingUrlPattern";

    private static final String DEFAULT_SERVER_URL = "http://localhost:7880";
    private static final String DEFAULT_MEETING_URL_PATTERN = "https://meet.example.com/?room={room}&token={token}";
    private static final String PLACEHOLDER_ROOM = "{room}";
    private static final String PLACEHOLDER_TOKEN = "{token}";
    private static final long DEFAULT_TOKEN_TTL = 21600L;

    private String _strName;
    private boolean _bDefault;

    // IVirtualMeetingProvider — identity

    @Override
    public String getName( )
    {
        return _strName;
    }

    public void setName( String strName )
    {
        _strName = strName;
    }

    @Override
    public boolean isDefault( )
    {
        return _bDefault;
    }

    public void setDefault( boolean bDefault )
    {
        _bDefault = bDefault;
    }

    // IVirtualMeetingProvider — meeting URL generation

    /**
     * {@inheritDoc}
     */
    @Override
    public String getParticipantMeetingUrl( String strRoomName, String strIdentity, String strName, Date notBefore )
    {
        String strToken = generateParticipantToken( strRoomName, strIdentity, strName, notBefore );
        return buildMeetingUrl( strRoomName, strToken );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getViewerMeetingUrl( String strRoomName, String strIdentity, String strName, Date notBefore )
    {
        String strToken = generateViewerToken( strRoomName, strIdentity, strName, notBefore );
        return buildMeetingUrl( strRoomName, strToken );
    }

    private String buildMeetingUrl( String strRoomName, String strToken )
    {
        String strPattern = AppPropertiesService.getProperty( PROPERTY_MEETING_URL_PATTERN, DEFAULT_MEETING_URL_PATTERN );
        return strPattern.replace( PLACEHOLDER_ROOM, strRoomName ).replace( PLACEHOLDER_TOKEN, strToken );
    }

    // Token generation

    /**
     * Generate a JWT token for a participant to join a room.
     *
     * @param strRoomName
     *            the room name
     * @param strIdentity
     *            the participant identity (unique user identifier)
     * @param strName
     *            the participant display name
     * @return the JWT token string
     */
    public String generateParticipantToken( String strRoomName, String strIdentity, String strName )
    {
        return generateParticipantToken( strRoomName, strIdentity, strName, null );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String generateParticipantToken( String strRoomName, String strIdentity, String strName, Date notBefore )
    {
        AccessToken token = createBaseToken( );

        token.setIdentity( strIdentity );
        token.setName( strName );

        if ( notBefore != null )
        {
            token.setNotBefore( notBefore );
        }

        token.addGrants( new RoomJoin( true ), new RoomName( strRoomName ), new CanPublish( true ), new CanSubscribe( true ), new CanPublishData( true ) );

        return token.toJwt( );
    }

    /**
     * Generate a JWT token for a participant with restricted permissions (subscribe only, no publish).
     *
     * @param strRoomName
     *            the room name
     * @param strIdentity
     *            the participant identity
     * @param strName
     *            the participant display name
     * @return the JWT token string
     */
    public String generateListenerToken( String strRoomName, String strIdentity, String strName )
    {
        return generateListenerToken( strRoomName, strIdentity, strName, null );
    }

    /**
     * Generate a JWT token for a listener, valid only from the given date.
     *
     * @param strRoomName
     *            the room name
     * @param strIdentity
     *            the participant identity
     * @param strName
     *            the participant display name
     * @param notBefore
     *            the earliest date/time the token becomes valid ({@code null} for immediate validity)
     * @return the JWT token string
     */
    public String generateListenerToken( String strRoomName, String strIdentity, String strName, Date notBefore )
    {
        AccessToken token = createBaseToken( );

        token.setIdentity( strIdentity );
        token.setName( strName );

        if ( notBefore != null )
        {
            token.setNotBefore( notBefore );
        }

        token.addGrants( new RoomJoin( true ), new RoomName( strRoomName ), new CanPublish( false ), new CanSubscribe( true ), new CanPublishData( false ) );

        return token.toJwt( );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String generateViewerToken( String strRoomName, String strIdentity, String strName, Date notBefore )
    {
        return generateListenerToken( strRoomName, strIdentity, strName, notBefore );
    }

    /**
     * Generate an admin-level JWT token with room management grants.
     *
     * @param strIdentity
     *            the admin identity
     * @param strName
     *            the admin display name
     * @return the JWT token string
     */
    public String generateAdminToken( String strIdentity, String strName )
    {
        return generateAdminToken( strIdentity, strName, null );
    }

    /**
     * Generate an admin-level JWT token with room management grants, valid only from the given date.
     *
     * @param strIdentity
     *            the admin identity
     * @param strName
     *            the admin display name
     * @param notBefore
     *            the earliest date/time the token becomes valid ({@code null} for immediate validity)
     * @return the JWT token string
     */
    public String generateAdminToken( String strIdentity, String strName, Date notBefore )
    {
        AccessToken token = createBaseToken( );

        token.setIdentity( strIdentity );
        token.setName( strName );

        if ( notBefore != null )
        {
            token.setNotBefore( notBefore );
        }

        token.addGrants( new RoomCreate( true ), new RoomList( true ), new RoomAdmin( true ) );

        return token.toJwt( );
    }

    // Room management

    /**
     * Create a new room on the LiveKit server.
     *
     * @param strRoomName
     *            the room name
     * @return {@code true} if the room was created successfully
     */
    public boolean createRoom( String strRoomName )
    {
        return createRoom( strRoomName, 0, 0 );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean createRoom( String strRoomName, int nEmptyTimeout, int nMaxParticipants )
    {
        try
        {
            RoomServiceClient client = getRoomServiceClient( );
            Call<LivekitModels.Room> call = client.createRoom( strRoomName, nEmptyTimeout > 0 ? nEmptyTimeout : null,
                    nMaxParticipants > 0 ? nMaxParticipants : null );
            Response<LivekitModels.Room> response = call.execute( );

            if ( response.isSuccessful( ) )
            {
                return true;
            }

            AppLogService.error( "LiveKit createRoom failed — HTTP {}: {}", response.code( ), response.message( ) );
        }
        catch( IOException e )
        {
            AppLogService.error( "LiveKit createRoom error", e );
        }

        return false;
    }

    /**
     * List all rooms on the LiveKit server.
     *
     * @return the list of rooms, or an empty list on error
     */
    public List<LivekitModels.Room> listRooms( )
    {
        try
        {
            RoomServiceClient client = getRoomServiceClient( );
            Response<List<LivekitModels.Room>> response = client.listRooms( ).execute( );

            if ( response.isSuccessful( ) && response.body( ) != null )
            {
                return response.body( );
            }

            AppLogService.error( "LiveKit listRooms failed — HTTP {}: {}", response.code( ), response.message( ) );
        }
        catch( IOException e )
        {
            AppLogService.error( "LiveKit listRooms error", e );
        }

        return Collections.emptyList( );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean deleteRoom( String strRoomName )
    {
        try
        {
            RoomServiceClient client = getRoomServiceClient( );
            Response<Void> response = client.deleteRoom( strRoomName ).execute( );

            if ( response.isSuccessful( ) )
            {
                return true;
            }

            AppLogService.error( "LiveKit deleteRoom failed — HTTP {}: {}", response.code( ), response.message( ) );
        }
        catch( IOException e )
        {
            AppLogService.error( "LiveKit deleteRoom error", e );
        }

        return false;
    }

    // Participant management

    /**
     * List all participants in a room.
     *
     * @param strRoomName
     *            the room name
     * @return the list of participants, or an empty list on error
     */
    public List<LivekitModels.ParticipantInfo> listParticipants( String strRoomName )
    {
        try
        {
            RoomServiceClient client = getRoomServiceClient( );
            Response<List<LivekitModels.ParticipantInfo>> response = client.listParticipants( strRoomName ).execute( );

            if ( response.isSuccessful( ) && response.body( ) != null )
            {
                return response.body( );
            }

            AppLogService.error( "LiveKit listParticipants failed — HTTP {}: {}", response.code( ), response.message( ) );
        }
        catch( IOException e )
        {
            AppLogService.error( "LiveKit listParticipants error", e );
        }

        return Collections.emptyList( );
    }

    /**
     * Get a specific participant's info from a room.
     *
     * @param strRoomName
     *            the room name
     * @param strIdentity
     *            the participant identity
     * @return the participant info, or {@code null} if not found
     */
    public LivekitModels.ParticipantInfo getParticipant( String strRoomName, String strIdentity )
    {
        try
        {
            RoomServiceClient client = getRoomServiceClient( );
            Response<LivekitModels.ParticipantInfo> response = client.getParticipant( strRoomName, strIdentity ).execute( );

            if ( response.isSuccessful( ) )
            {
                return response.body( );
            }

            AppLogService.error( "LiveKit getParticipant failed — HTTP {}: {}", response.code( ), response.message( ) );
        }
        catch( IOException e )
        {
            AppLogService.error( "LiveKit getParticipant error", e );
        }

        return null;
    }

    /**
     * Remove a participant from a room.
     *
     * @param strRoomName
     *            the room name
     * @param strIdentity
     *            the participant identity
     * @return {@code true} if the participant was removed successfully
     */
    public boolean removeParticipant( String strRoomName, String strIdentity )
    {
        try
        {
            RoomServiceClient client = getRoomServiceClient( );
            Response<Void> response = client.removeParticipant( strRoomName, strIdentity ).execute( );

            if ( response.isSuccessful( ) )
            {
                return true;
            }

            AppLogService.error( "LiveKit removeParticipant failed — HTTP {}: {}", response.code( ), response.message( ) );
        }
        catch( IOException e )
        {
            AppLogService.error( "LiveKit removeParticipant error", e );
        }

        return false;
    }

    /**
     * Update the metadata of a room.
     *
     * @param strRoomName
     *            the room name
     * @param strMetadata
     *            the new metadata string
     * @return the updated Room, or {@code null} on error
     */
    public LivekitModels.Room updateRoomMetadata( String strRoomName, String strMetadata )
    {
        try
        {
            RoomServiceClient client = getRoomServiceClient( );
            Response<LivekitModels.Room> response = client.updateRoomMetadata( strRoomName, strMetadata ).execute( );

            if ( response.isSuccessful( ) )
            {
                return response.body( );
            }

            AppLogService.error( "LiveKit updateRoomMetadata failed — HTTP {}: {}", response.code( ), response.message( ) );
        }
        catch( IOException e )
        {
            AppLogService.error( "LiveKit updateRoomMetadata error", e );
        }

        return null;
    }

    // Internal helpers

    /**
     * Create a base AccessToken configured with API credentials and TTL.
     */
    private AccessToken createBaseToken( )
    {
        String strApiKey = AppPropertiesService.getProperty( PROPERTY_API_KEY );
        String strApiSecret = AppPropertiesService.getProperty( PROPERTY_API_SECRET );
        long lTtl = AppPropertiesService.getPropertyLong( PROPERTY_TOKEN_TTL, DEFAULT_TOKEN_TTL );

        AccessToken token = new AccessToken( strApiKey, strApiSecret );
        token.setTtl( lTtl );

        return token;
    }

    /**
     * Create a RoomServiceClient from the configured properties.
     */
    private RoomServiceClient getRoomServiceClient( )
    {
        String strServerUrl = AppPropertiesService.getProperty( PROPERTY_SERVER_URL, DEFAULT_SERVER_URL );
        String strApiKey = AppPropertiesService.getProperty( PROPERTY_API_KEY );
        String strApiSecret = AppPropertiesService.getProperty( PROPERTY_API_SECRET );

        return RoomServiceClient.createClient( strServerUrl, strApiKey, strApiSecret );
    }
}

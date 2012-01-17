/**
 * Copyright (c) 2010-2009 The Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sakaiproject.bbb.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.sakaiproject.bbb.api.BBBMeeting;
import org.sakaiproject.bbb.api.Participant;
import org.sakaiproject.bbb.impl.sql.DefaultSqlGenerator;
import org.sakaiproject.bbb.impl.sql.HypersonicGenerator;
import org.sakaiproject.bbb.impl.sql.MySQLGenerator;
import org.sakaiproject.bbb.impl.sql.OracleGenerator;
import org.sakaiproject.bbb.impl.sql.SqlGenerator;
import org.sakaiproject.bbb.impl.util.XmlUtil;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.db.api.SqlService;

/**
 * BBBStorageManager is responsible for interacting with Sakai database.
 * 
 * @author Adrian Fish
 */
public class BBBStorageManager {
    protected final Logger logger = Logger.getLogger(getClass());

    public SqlService sqlService = null;
    private ServerConfigurationService serverConfigurationService = null;

    private SqlGenerator sqlGenerator = null;

    // -----------------------------------------------------------------------
    // --- Initialization related methods ------------------------------------
    // -----------------------------------------------------------------------
    public void init() {
        if (logger.isDebugEnabled())
            logger.debug("init()");

        String dbVendor = sqlService.getVendor();
        if (dbVendor.equals("mysql"))
            sqlGenerator = new MySQLGenerator();
        else if (dbVendor.equals("oracle"))
            sqlGenerator = new OracleGenerator();
        else if (dbVendor.equals("hsqldb"))
            sqlGenerator = new HypersonicGenerator();
        else {
            logger.warn("'" + dbVendor + "' not directly supported. Defaulting to DefaultSqlGenerator.");
            sqlGenerator = new DefaultSqlGenerator();
        }
    }

    public void setSqlService(SqlService sqlService) {
        this.sqlService = sqlService;
    }

    public void setServerConfigurationService(ServerConfigurationService serverConfigurationService) {
        this.serverConfigurationService = serverConfigurationService;
    }

    // -----------------------------------------------------------------------
    // --- BBB Storage Manager implementation methods ------------------------
    // -----------------------------------------------------------------------
    /**
     * If auto.ddl is set the bbb tables are created
     */
    public void setupTables() {
        if (logger.isDebugEnabled())
            logger.debug("setupTables()");

        // check for auto.ddl
        String autoDDL = serverConfigurationService.getString("auto.ddl");
        if (!autoDDL.equals("true")) {
            if (logger.isDebugEnabled())
                logger.debug("auto.ddl is set to false. Skipping...");
            return;
        }

        // create tables
        Connection connection = null;
        Statement statement = null;
        try {
            connection = sqlService.borrowConnection();
            boolean oldAutoCommitFlag = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                statement = connection.createStatement();

                Map<String, String> statements = sqlGenerator.getSetupStatements();
                for (String tableName : statements.keySet()) {
                    boolean create = true;

                    // Does the table already exist?
                    String showTable = sqlGenerator
                            .getShowTableStatement(tableName);
                    if (showTable != null) {
                        ResultSet rs = statement.executeQuery(showTable);
                        if (rs.next()) {
                            create = false;
                            logger.debug("Table " + tableName + " already exists in DB.");
                        }
                    }

                    // Create table
                    if (create) {
                        logger.info("Creating table " + tableName + " in DB.");
                        statement.executeUpdate(statements.get(tableName));
                    }
                }

                connection.commit();
                
                //Code for database update starts here
                statement = connection.createStatement();

                Map<String, String> updateStatements = sqlGenerator.getUpdateStatements();
                for (String updateName : updateStatements.keySet()) {
                    boolean update = false;

                    // Does the table need to be updated?
                    String showNewColumn = sqlGenerator.getShowNewColumnStatement(updateName);
                    if (showNewColumn != null) {
                        ResultSet rs = statement.executeQuery(showNewColumn);
                        if (!rs.next()) {
                            update = true;
                        }
                    
                    } 

                    if ( update ){
                        // Update table
                        logger.info("Updating " + updateName + " in DB.");
                        statement.executeUpdate(updateStatements.get(updateName));
                    } else {
                        // Column it does exist. Table doesn't need to be updated
                        logger.info("Update " + updateName + " does not need to be applied in DB.");

                    }
                }

                connection.commit();
            } catch (SQLException sqle) {
                logger.error("Caught exception whilst setting up tables. Rolling back ...", sqle);
                connection.rollback();
            } finally {
                connection.setAutoCommit(oldAutoCommitFlag);
            }
        } catch (Exception e) {
            logger.error("Caught exception whilst setting up tables");
        } finally {
            try {
                if (statement != null)
                    statement.close();
            } catch (Exception e) {
            }

            sqlService.returnConnection(connection);
        }
    }
    
    private boolean tableShouldBeUpdated(String tableName){
        return true;
    }

    public boolean storeMeeting(BBBMeeting meeting) {
        Connection connection = null;
        List<PreparedStatement> statements = null;

        try {
            connection = sqlService.borrowConnection();
            boolean oldAutoCommitFlag = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                statements = sqlGenerator.getStoreMeetingStatements(meeting,connection);

                for (PreparedStatement statement : statements)
                    statement.execute();

                connection.commit();

                return true;
            } catch (SQLException sqle) {
                logger.error("Caught exception whilst storing meeting. Rolling back ...", sqle);
                connection.rollback();
            } finally {
                connection.setAutoCommit(oldAutoCommitFlag);
            }
        } catch (Exception e) {
            logger.error("Caught exception whilst storing meeting.", e);
        } finally {
            if (statements != null) {
                try {
                    for (PreparedStatement statement : statements)
                        statement.close();
                } catch (Exception e) {
                }
            }

            sqlService.returnConnection(connection);
        }

        return false;
    }

    public boolean updateMeeting(BBBMeeting meeting, boolean updateParticipants) {
        Connection connection = null;
        List<PreparedStatement> statements = null;

        try {
            connection = sqlService.borrowConnection();
            boolean oldAutoCommitFlag = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                statements = sqlGenerator.getUpdateMeetingStatements(meeting,
                        connection);
                if (updateParticipants) {
                    statements.addAll(sqlGenerator
                            .getUpdateMeetingParticipantsStatements(meeting,
                                    connection));
                }

                for (PreparedStatement statement : statements)
                    statement.execute();

                connection.commit();

                return true;
            } catch (SQLException sqle) {
                logger.error("Caught exception whilst updating meeting. Rolling back ...", sqle);
                connection.rollback();
            } finally {
                connection.setAutoCommit(oldAutoCommitFlag);
            }
        } catch (Exception e) {
            logger.error("Caught exception whilst updating meeting.", e);
        } finally {
            if (statements != null) {
                try {
                    for (PreparedStatement statement : statements)
                        statement.close();
                } catch (Exception e) {
                }
            }

            sqlService.returnConnection(connection);
        }

        return false;
    }

    public List<BBBMeeting> getSiteMeetings(String siteId) {
        List<BBBMeeting> meetings = new ArrayList<BBBMeeting>();

        Connection connection = null;
        Statement meetingST = null;

        try {
            connection = sqlService.borrowConnection();
            meetingST = connection.createStatement();
            String sql = sqlGenerator.getSelectSiteMeetingsStatement(siteId);
            ResultSet meetingRS = meetingST.executeQuery(sql);

            while (meetingRS.next()) {
                BBBMeeting meeting = meetingFromResultSet(meetingRS, connection);
                meetings.add(meeting);
            }

            meetingRS.close();

            return meetings;
        } catch (Exception e) {
            logger.error("Caught exception whilst getting site meetings.", e);
        } finally {
            try {
                if (meetingST != null)
                    meetingST.close();
            } catch (Exception e) {
            }

            sqlService.returnConnection(connection);
        }

        return null;
    }

    public BBBMeeting getMeeting(String meetingId) {
        Connection connection = null;
        Statement meetingST = null;
        BBBMeeting meeting = null;

        try {
            connection = sqlService.borrowConnection();
            meetingST = connection.createStatement();
            String sql = sqlGenerator.getSelectMeetingStatement(meetingId);
            ResultSet meetingRS = meetingST.executeQuery(sql);

            if (meetingRS.next())
                meeting = meetingFromResultSet(meetingRS, connection);

            meetingRS.close();

        } catch (Exception e) {
            logger.error("Caught exception whilst getting site meetings.", e);
        } finally {
            try {
                if (meetingST != null)
                    meetingST.close();
            } catch (Exception e) {
            }

            sqlService.returnConnection(connection);
        }

        return meeting;

    }

    private BBBMeeting meetingFromResultSet(ResultSet meetingRS, Connection connection) throws Exception {
        BBBMeeting meeting = null;

        Statement participantST = null;

        try {
            participantST = connection.createStatement();
            meeting = new BBBMeeting();

            meeting.setId(meetingRS.getString("MEETING_ID"));
            meeting.setName(meetingRS.getString("NAME"));
            meeting.setHostUrl(meetingRS.getString("HOST_URL"));
            meeting.setSiteId(meetingRS.getString("SITE_ID"));
            meeting.setAttendeePassword(meetingRS.getString("ATTENDEE_PW"));
            meeting.setModeratorPassword(meetingRS.getString("MODERATOR_PW"));
            meeting.setOwnerId(meetingRS.getString("OWNER_ID"));
            meeting.setStartDate(meetingRS.getTimestamp("START_DATE"));
            meeting.setEndDate(meetingRS.getTimestamp("END_DATE"));
            meeting.setRecording(meetingRS.getBoolean("RECORDING"));
            meeting.setRecordingDuration(meetingRS.getLong("RECORDING_DURATION"));
            meeting.setProps(XmlUtil.convertXmlToProps(meetingRS.getString("PROPERTIES")));
            meeting.setDeleted(meetingRS.getBoolean("DELETED"));
            String particpantSql = sqlGenerator.getSelectMeetingParticipantsStatement(meeting.getId());
            ResultSet participantsRS = participantST.executeQuery(particpantSql);
            List<Participant> participants = new ArrayList<Participant>();
            while (participantsRS.next()) {
                Participant p = new Participant();
                p.setSelectionType(participantsRS.getString("SELECTION_TYPE"));
                p.setSelectionId(participantsRS.getString("SELECTION_ID"));
                p.setRole(participantsRS.getString("ROLE"));
                participants.add(p);
            }

            participantsRS.close();

            meeting.setParticipants(participants);

        } finally {
            if (participantST != null)
                participantST.close();
        }

        return meeting;
    }

    public boolean deleteMeeting(String meetingId) {
        Connection connection = null;
        List<PreparedStatement> statements = null;

        try {
            connection = sqlService.borrowConnection();
            boolean oldAutoCommitFlag = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                //statements = sqlGenerator.getDeleteMeetingStatements(meetingId, connection);
                statements = sqlGenerator.getMarkMeetingAsDeletedStatements(meetingId, connection);

                for (PreparedStatement statement : statements)
                    statement.execute();

                connection.commit();

                return true;
            } catch (SQLException sqle) {
                logger.error("Caught exception whilst deleting meeting. Rolling back ...", sqle);
                connection.rollback();
            } finally {
                connection.setAutoCommit(oldAutoCommitFlag);
            }
        } catch (Exception e) {
            logger.error("Caught exception whilst deleting meeting.", e);
        } finally {
            if (statements != null) {
                try {
                    for (PreparedStatement statement : statements)
                        statement.close();
                } catch (Exception e) {
                }
            }

            sqlService.returnConnection(connection);
        }

        return false;
    }

    public String getMeetingHost(String meetingID) {
        Connection connection = null;
        Statement st = null;
        ResultSet rs = null;
        BBBMeeting meeting = null;

        try {
            connection = sqlService.borrowConnection();
            st = connection.createStatement();
            String sql = sqlGenerator.getSelectMeetingHostStatement(meetingID);
            rs = st.executeQuery(sql);

            if (rs.next())
                return rs.getString("HOST_URL");
        } catch (Exception e) {
            logger.error("Caught exception whilst getting site meetings.", e);
        } finally {
            try {
                if (rs != null)
                    rs.close();
            } catch (Exception e) {
            }

            try {
                if (st != null)
                    st.close();
            } catch (Exception e) {
            }

            sqlService.returnConnection(connection);
        }

        return null;
    }

    public List<BBBMeeting> getAllMeetings() {
        List<BBBMeeting> meetings = new ArrayList<BBBMeeting>();

        Connection connection = null;
        Statement meetingST = null;

        try {
            connection = sqlService.borrowConnection();
            meetingST = connection.createStatement();
            String sql = sqlGenerator.getSelectAllMeetingsStatement();
            ResultSet meetingRS = meetingST.executeQuery(sql);

            while (meetingRS.next()) {
                BBBMeeting meeting = meetingFromResultSet(meetingRS, connection);
                meetings.add(meeting);
            }

            meetingRS.close();

            return meetings;
        } catch (Exception e) {
            logger.error("Caught exception whilst getting site meetings.", e);
        } finally {
            try {
                if (meetingST != null)
                    meetingST.close();
            } catch (Exception e) {
            }

            sqlService.returnConnection(connection);
        }

        return null;
    }

    public boolean setMeetingHost(String meetingId, String url) {
        Connection connection = null;
        PreparedStatement st = null;

        try {
            connection = sqlService.borrowConnection();
            st = sqlGenerator.getUpdateHostForMeetingStatement(meetingId, url,
                    connection);
            if (st.executeUpdate() == 1)
                return true;
            else
                return false;
        } catch (Exception e) {
            logger.error("Caught exception whilst updating meeting host.", e);
        } finally {
            if (st != null) {
                try {
                    st.close();
                } catch (Exception e) {
                }
            }

            sqlService.returnConnection(connection);
        }

        return false;
    }
}
package org.transmartproject.batch.browsetag

import groovy.util.logging.Slf4j
import org.springframework.batch.item.ItemStreamReader
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.transmartproject.batch.clinical.db.objects.Tables
import org.transmartproject.batch.concept.ConceptPath

import javax.annotation.PostConstruct
import javax.sql.DataSource
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Gets the browse tags, associated with folders, from database.
 */
@Slf4j
class BrowseTagAssociationDatabaseReader implements ItemStreamReader<BrowseTagAssociation> {

    @Value("#{jobParameters['STUDY_ID']}")
    String studyId

    @Autowired
    NamedParameterJdbcTemplate jdbcTemplate

    @Delegate
    JdbcCursorItemReader<BrowseTagAssociation> delegate

    @Autowired
    DataSource dataSource

    @PostConstruct
    void init() {
        delegate = new JdbcCursorItemReader<>(
                driverSupportsAbsolute: true,
                dataSource: dataSource,
                sql: sql,
                preparedStatementSetter: this.&setStudyId as PreparedStatementSetter,
                rowMapper: this.&mapRow as RowMapper<BrowseTagAssociation>)

        delegate.afterPropertiesSet()
    }

    void setStudyId(PreparedStatement ps) throws SQLException {
        log.info "Study ID: ${studyId}"
        ps.setString(1, studyId)
        ps.setString(2, studyId)
    }

    private String getSql() {
        /*
            Table {@link $Tables.BIO_CONCEPT_CODE}:
            primary key: bio_concept_code_id
            unique: (code_type_name, bio_concept_code)
            index: code_type_name

            Table {@link $Tables.AM_TAG_ITEM}:
            primary key: (tag_template_id, tag_item_id)
         */

        """
                (SELECT
                    f.folder_id,
                    f.folder_name,
                    f.folder_full_name,
                    f.folder_level,
                    f.folder_type,
                    f.folder_tag,
                    f.parent_id,
                    f.description as folder_description,
                    fp.folder_name as parent_name,
                    fp.folder_full_name as parent_full_name,
                    fp.folder_type as parent_type,
                    ati.tag_template_id,
                    ati.tag_item_id,
                    ati.tag_item_uid,
                    ati.display_name,
                    ati.tag_item_type,
                    ati.tag_item_subtype,
                    ati.code_type_name,
                    ati.required,
                    tv.value as value,
                    tv.value as description
                FROM $Tables.FM_FOLDER f
                INNER JOIN $Tables.FM_DATA_UID fuid
                ON f.folder_id = fuid.fm_data_id
                INNER JOIN $Tables.AM_TAG_ASSOCIATION ata
                ON fuid.unique_id = ata.subject_uid
                INNER JOIN $Tables.AM_TAG_ITEM ati
                ON ata.tag_item_id = ati.tag_item_id
                INNER JOIN $Tables.AM_DATA_UID tuid
                ON ata.object_uid = tuid.unique_id
                INNER JOIN $Tables.AM_TAG_VALUE tv
                ON tuid.am_data_id = tv.tag_value_id
                LEFT OUTER JOIN $Tables.FM_FOLDER fp
                ON f.parent_id = fp.folder_id
                WHERE ata.object_type = 'AM_TAG_VALUE'
                AND f.folder_type = 'STUDY'
                AND UPPER(f.folder_name) = ?
                )
                UNION
                (SELECT
                    f.folder_id,
                    f.folder_name,
                    f.folder_full_name,
                    f.folder_level,
                    f.folder_type,
                    f.folder_tag,
                    f.parent_id,
                    f.description as folder_description,
                    fp.folder_name as parent_name,
                    fp.folder_full_name as parent_full_name,
                    fp.folder_type as parent_type,
                    ati.tag_template_id,
                    ati.tag_item_id,
                    ati.tag_item_uid,
                    ati.display_name,
                    ati.tag_item_type,
                    ati.tag_item_subtype,
                    ati.code_type_name,
                    ati.required,
                    bcc.bio_concept_code as value,
                    bcc.code_description as description
                FROM $Tables.FM_FOLDER f
                INNER JOIN $Tables.FM_DATA_UID fuid
                ON f.folder_id = fuid.fm_data_id
                INNER JOIN $Tables.AM_TAG_ASSOCIATION ata
                ON fuid.unique_id = ata.subject_uid
                INNER JOIN $Tables.AM_TAG_ITEM ati
                ON ata.tag_item_id = ati.tag_item_id
                INNER JOIN $Tables.BIO_CONCEPT_CODE bcc
                ON ata.object_uid = concat(bcc.code_type_name, concat(':', bcc.bio_concept_code))
                LEFT OUTER JOIN $Tables.FM_FOLDER fp
                ON f.parent_id = fp.folder_id
                WHERE ata.object_type = 'BIO_CONCEPT_CODE'
                AND f.folder_type = 'STUDY'
                AND UPPER(f.folder_name) = ?
                )
        """
    }

    private final Map<String, BrowseFolderType> folderTypes = [:]

    private BrowseFolderType getFolderType(ResultSet rs) {
        String folderTypeName = rs.getString('folder_type')
        BrowseFolderType folderType = folderTypes[folderTypeName]
        if (folderType == null) {
            folderType = new BrowseFolderType(
                    type: folderTypeName,
                    displayName: rs.getString('folder_description')
            )
            folderTypes[folderTypeName] = folderType
        }
        folderType
    }

    private final Map<Long, BrowseTagType> tagTypes = [:]

    private BrowseTagType getTagType(ResultSet rs) {
        Long tagItemId = rs.getLong('tag_item_id')
        BrowseTagType tagType = tagTypes[tagItemId]
        if (tagType == null) {
            tagType = new BrowseTagType(
                    code: rs.getString('code_type_name'),
                    folderType: getFolderType(rs),
                    type: rs.getString('tag_item_type'),
                    subType: rs.getString('tag_item_subtype'),
                    displayName: rs.getString('display_name'),
                    required: rs.getBoolean('required')
            )
            tagTypes[tagItemId] = tagType
        }
        tagType
    }

    private final Map<String, BrowseFolder> folders = [:]

    private BrowseFolder getFolder(ResultSet rs) {
        String fullName = rs.getString('folder_full_name')
        BrowseFolder folder = folders[fullName]
        if (folder == null) {
            folder = new BrowseFolder(
                    fullName: fullName,
                    id: rs.getLong('folder_id'),
                    name: rs.getString('folder_name'),
                    type: getFolderType(rs),
                    level: rs.getInt('folder_level'),
                    description: rs.getString('folder_description'),
                    parent: rs.getString('parent_name')
            )
            folders[fullName] = folder
        }
        folder
    }

    private BrowseTagValue getValue(ResultSet rs) {
        new BrowseTagValue(
                type: getTagType(rs),
                value: rs.getString('value'),
                description: rs.getString('description')
        )
    }

    @SuppressWarnings('UnusedPrivateMethodParameter')
    private BrowseTagAssociation mapRow(ResultSet rs, int rowNum) throws SQLException {
        new BrowseTagAssociation(
                folder: getFolder(rs),
                value: getValue(rs)
        )
    }

}

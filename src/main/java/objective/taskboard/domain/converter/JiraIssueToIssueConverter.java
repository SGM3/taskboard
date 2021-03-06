package objective.taskboard.domain.converter;

/*-
 * [LICENSE]
 * Taskboard
 * - - -
 * Copyright (C) 2015 - 2016 Objective Solutions
 * - - -
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * [/LICENSE]
 */

import static com.google.common.collect.Lists.newArrayList;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.atlassian.jira.rest.client.api.domain.Comment;
import com.atlassian.jira.rest.client.api.domain.IssueField;
import com.atlassian.jira.rest.client.api.domain.IssueLink;
import com.atlassian.jira.rest.client.api.domain.IssueLinkType.Direction;
import com.atlassian.jira.rest.client.api.domain.User;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.extern.slf4j.Slf4j;
import objective.taskboard.data.Issue;
import objective.taskboard.data.Team;
import objective.taskboard.data.UserTeam;
import objective.taskboard.domain.IssueColorService;
import objective.taskboard.domain.ParentIssueLink;
import objective.taskboard.filterConfiguration.TeamFilterConfigurationService;
import objective.taskboard.jira.JiraProperties;
import objective.taskboard.jira.JiraService;
import objective.taskboard.repository.ParentIssueLinkRepository;
import objective.taskboard.repository.UserTeamCachedRepository;

@Slf4j
@Service
public class JiraIssueToIssueConverter {

    private final String SEM_TIME = "NO TEAM";

    @Autowired
    private UserTeamCachedRepository userTeamRepository;

    @Autowired
    private TeamFilterConfigurationService teamFilterConfigurationService;

    @Autowired
    private ParentIssueLinkRepository parentIssueLinkRepository;

    @Autowired
    private JiraService jiraService;
    
    @Autowired
    private JiraProperties jiraProperties;
    
    @Autowired
    private IssueColorService issueColorService;
    
    private List<String> parentIssueLinks;
    private List<com.atlassian.jira.rest.client.api.domain.Issue> issuesList;
    private List<String> usersInvalidTeam;

    @PostConstruct
    private void loadParentIssueLinks() {
        parentIssueLinks = parentIssueLinkRepository.findAll().stream()
                               .map(ParentIssueLink::getDescriptionIssueLink)
                               .collect(Collectors.toList());
    }

    public List<Issue> convert(List<com.atlassian.jira.rest.client.api.domain.Issue> issueList) {
        issuesList = issueList;
        List<Issue> collect = issueList.stream()
                                  .map(this::convert)
                                  .collect(Collectors.toList());
        System.out.println(collect.size());
        return collect;
    }

    public Issue convert(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        List<String> avatarSubResponsaveis = getSubResponsaveisAvatar(jiraIssue);
        List<String> nameSubResponsaveis = getSubResponsaveisName(jiraIssue);

        String subResponsavel1 = jiraIssue.getAssignee() != null ? jiraIssue.getAssignee().getAvatarUri("24x24").toString() : "";
        String subResponsavel2 = avatarSubResponsaveis.stream().filter(x -> !x.equals(subResponsavel1)).findFirst().orElse("");

        Map<String, Object> customFields = Maps.newHashMap();

        customFields.put(jiraProperties.getCustomfield().getTShirtSize().getId(), getTamanho(jiraIssue));
        customFields.put(jiraProperties.getCustomfield().getClassOfService().getId(), getClasseDeServico(jiraIssue));
        customFields.put(jiraProperties.getCustomfield().getBlocked().getId(), getBlocked(jiraIssue));
        customFields.put(jiraProperties.getCustomfield().getLastBlockReason().getId(), getLastBlockReason(jiraIssue));

        List<String> teamGroups = getTeamGroups(jiraIssue);

        usersInvalidTeam = usersInvalidTeam.stream()
                               .distinct()
                               .collect(Collectors.toList());

        long parentTypeId = getParentType(jiraIssue);
        Long issueTypeId = jiraIssue.getIssueType().getId();
        String color = issueColorService.getColor(issueTypeId, parentTypeId);
        return Issue.from(
                jiraIssue.getKey(),
                jiraIssue.getProject().getKey(),
                jiraIssue.getProject().getName(),
                issueTypeId,
                jiraIssue.getIssueType().getIconUri().toASCIIString(),
                jiraIssue.getSummary() != null ? jiraIssue.getSummary() : "",
                jiraIssue.getStatus().getId(),
                subResponsavel1,
                subResponsavel2,
                getParentKey(jiraIssue),
                parentTypeId,
                getParentTypeIconUri(jiraIssue),
                getRequired(jiraIssue),
                String.join(",", nameSubResponsaveis),
                jiraIssue.getAssignee() != null ? jiraIssue.getAssignee().getName() : "",
                String.join(",", usersInvalidTeam),
                jiraIssue.getPriority() != null ? jiraIssue.getPriority().getId() : 0l,
                jiraIssue.getDueDate() != null ? jiraIssue.getDueDate().toDate() : null,
                jiraIssue.getDescription() != null ? jiraIssue.getDescription() : "",
                teamGroups,
                getComments(jiraIssue),
                customFields,
                color
        );
    }

    private long getParentType(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        IssueField field = jiraIssue.getField("parent");

        if (field == null)
            return 0;

        JSONObject json = (JSONObject) field.getValue();

        try {
            return (json != null) ? json.getJSONObject("fields").getJSONObject("issuetype").getLong("id") : 0;
        } catch (JSONException e) {
            e.printStackTrace();
            log.error(e.getMessage(), e);
            return 0;
        }
    }

    private String getParentTypeIconUri(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        IssueField field = jiraIssue.getField("parent");
        if (field == null)
            return "";

        JSONObject json = (JSONObject) field.getValue();
        if (json != null) {
            try {
                return json.getJSONObject("fields").getJSONObject("issuetype").getString("iconUrl");
            } catch (JSONException e) {
                log.error(e.getMessage(), e);
            }
        }

        return "";
    }

    private String getParentKey(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        IssueField field = jiraIssue.getField("parent");

        if (field == null) {
            if (jiraIssue.getIssueLinks() != null) {
                List<IssueLink> links = Lists.newArrayList(jiraIssue.getIssueLinks());
                List<IssueLink> collect = links.stream()
                        .filter(l -> parentIssueLinks.contains(l.getIssueLinkType().getDescription()))
                        .collect(Collectors.toList());
                if (!collect.isEmpty()) {
                    IssueLink link = collect.stream().findFirst().orElse(null);
                    return (link != null) ? link.getTargetIssueKey() : "";
                }
            }
            return "";
        }

        JSONObject json = (JSONObject) field.getValue();

        try {
            return (json != null) ? json.getString("key") : "";
        } catch (JSONException e) {
            log.error(e.getMessage(), e);
            return "";
        }
    }

    private List<String> getRequired(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        return Lists.newArrayList(jiraIssue.getIssueLinks()).stream()
                .filter(this::isRequiresLink)
                .map(link -> link.getTargetIssueKey())
                .collect(Collectors.toList());
    }

    private boolean isRequiresLink(final IssueLink link) {
         return link.getIssueLinkType().getName().equals(jiraProperties.getIssuelink().getRequirement().getName())
             && link.getIssueLinkType().getDirection() == Direction.OUTBOUND;
    }

    private String getComments(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        Iterable<Comment> comment = jiraIssue.getComments();
        if (Lists.newArrayList(comment).size() == 0)
            return "";
        else
            return comment.iterator().next().toString();
    }

    private String getTamanho(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        return getJsonValue(jiraIssue, jiraProperties.getCustomfield().getTShirtSize().getId());
    }

    private String getClasseDeServico(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        return getJsonValue(jiraIssue, jiraProperties.getCustomfield().getClassOfService().getId());
    }

    private String getJsonValue(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue, String fieldId) {
        IssueField field = jiraIssue.getField(fieldId);

        if (field == null)
            return "";

        JSONObject json = (JSONObject) field.getValue();

        try {
            return (json != null) ? json.getString("value") : "";
        } catch (JSONException e) {
            log.error(e.getMessage(), e);
            return "";
        }
    }

    private String getBlocked(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        IssueField field = jiraIssue.getField(jiraProperties.getCustomfield().getBlocked().getId());

        if (field == null) return "";

        JSONArray jsonArray = (JSONArray) field.getValue();

        try {
            return (jsonArray != null) ? jsonArray.getJSONObject(0).getString("value") : "";
        } catch (JSONException e) {
            log.error(e.getMessage(), e);
            return "";
        }
    }

    private String getLastBlockReason(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        IssueField field = jiraIssue.getField(jiraProperties.getCustomfield().getLastBlockReason().getId());

        if (field == null || field.getValue() == null) return "";

        String lastBlockReason = field.getValue().toString();
        return lastBlockReason.length() > 200 ? lastBlockReason.substring(0, 200) + "..." : lastBlockReason;
    }

    private List<String> getSubResponsaveisAvatar(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        IssueField field = jiraIssue.getField(jiraProperties.getCustomfield().getCoAssignees().getId());
        List<String> names = Lists.newArrayList();

        if (field == null)
            return names;

        JSONArray value = (JSONArray) field.getValue();

        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                try {
                    names.add(value.getJSONObject(i).getJSONObject("avatarUrls").getString("24x24"));
                } catch (JSONException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }

        return names;
    }

    private List<String> getSubResponsaveisName(com.atlassian.jira.rest.client.api.domain.Issue jiraIssue) {
        IssueField field = jiraIssue.getField(jiraProperties.getCustomfield().getCoAssignees().getId());
        List<String> names = Lists.newArrayList();

        if (field == null)
            return names;

        JSONArray value = (JSONArray) field.getValue();

        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                try {
                    names.add(value.getJSONObject(i).getString("name"));
                } catch (JSONException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }

        return names;
    }

    private List<String> getTeamGroups(com.atlassian.jira.rest.client.api.domain.Issue issue) {
        List<UserTeam> users = getUsers(issue);
        if (users.isEmpty())
            return newArrayList(SEM_TIME);

        return users.stream()
                .filter(Objects::nonNull)
                .map(u -> u.getTeam())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<UserTeam> getUsers(com.atlassian.jira.rest.client.api.domain.Issue issue) {
        usersInvalidTeam = newArrayList();
        List<UserTeam> users = getUsersResponsaveis(issue);
        if (foundSomeUser(users))
            return users;

        users = getParentUsersResponsaveis(issue);
        if (foundSomeUser(users))
            return users;

        User reporter = issue.getReporter();
        if (reporter != null)
            users.addAll(getUserTeam(reporter.getName()));
        return users;
    }

    private List<UserTeam> getUsersResponsaveis(com.atlassian.jira.rest.client.api.domain.Issue issue) {
        List<UserTeam> users = newArrayList();
        User assignee = issue.getAssignee();
        if (assignee != null)
            users.addAll(getUserTeam(assignee.getName()));

        for (String subResponsavel : getSubResponsaveisName(issue))
            users.addAll(getUserTeam(subResponsavel));

        return users;
    }

    private boolean foundSomeUser(List<UserTeam> users) {
        return !users.isEmpty() || !usersInvalidTeam.isEmpty();
    }

    private List<UserTeam> getUserTeam(String userName) {
        UserTeam userTeam = userTeamRepository.findByUserName(userName);
        if (userTeam != null && isTeamVisible(userTeam.getTeam()))
            return newArrayList(userTeam);

        usersInvalidTeam.add(userName);
        return newArrayList();
    }

    private List<UserTeam> getParentUsersResponsaveis(com.atlassian.jira.rest.client.api.domain.Issue issue) {
        List<UserTeam> parentUsers = newArrayList();
        String parentKey = getParentKey(issue);
        if (Strings.isNullOrEmpty(parentKey))
            return parentUsers;

        com.atlassian.jira.rest.client.api.domain.Issue parent = getIssueByKey(parentKey);
        if (parent != null)
            parentUsers = getUsersResponsaveis(parent);
        return parentUsers;
    }

    private com.atlassian.jira.rest.client.api.domain.Issue getIssueByKey(String parentKey) {
        com.atlassian.jira.rest.client.api.domain.Issue parent = issuesList.stream()
                .filter(i -> i.getKey().equals(parentKey))
                .findFirst()
                .orElse(null);

        if (parent != null)
            return parent;

        return jiraService.getIssueByKey(parentKey);
    }

    private boolean isTeamVisible(String team) {
        List<Team> visibleTeams = teamFilterConfigurationService.getVisibleTeams();
        return visibleTeams.stream()
                .filter(t -> Objects.equals(t.getName(), team))
                .findFirst()
                .isPresent();
    }

}

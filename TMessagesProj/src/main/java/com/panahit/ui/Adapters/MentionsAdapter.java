/*
 * This is the source code of Telegram for Android v. 3.x.x
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2016.
 */

package com.panahit.ui.Adapters;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.panahit.SQLite.SQLiteCursor;
import com.panahit.telegramma.AndroidUtilities;
import com.panahit.telegramma.MessagesController;
import com.panahit.telegramma.UserObject;
import com.panahit.telegramma.support.widget.LinearLayoutManager;
import com.panahit.tgnet.TLObject;
import com.panahit.tgnet.TLRPC;
import com.panahit.SQLite.SQLitePreparedStatement;
import com.panahit.telegramma.FileLog;
import com.panahit.telegramma.MessageObject;
import com.panahit.telegramma.MessagesStorage;
import com.panahit.telegramma.support.widget.RecyclerView;
import com.panahit.tgnet.ConnectionsManager;
import com.panahit.tgnet.RequestDelegate;
import com.panahit.ui.Cells.ContextLinkCell;
import com.panahit.ui.Cells.MentionCell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

public class MentionsAdapter extends BaseSearchAdapterRecycler {

    public interface MentionsAdapterDelegate {
        void needChangePanelVisibility(boolean show);
        void onContextSearch(boolean searching);
        void onContextClick(TLRPC.BotInlineResult result);
    }

    private class Holder extends RecyclerView.ViewHolder {

        public Holder(View itemView) {
            super(itemView);
        }
    }

    private Context mContext;
    private TLRPC.ChatFull info;
    private ArrayList<TLRPC.User> botRecent;
    private ArrayList<TLRPC.User> searchResultUsernames;
    private ArrayList<String> searchResultHashtags;
    private ArrayList<String> searchResultCommands;
    private ArrayList<String> searchResultCommandsHelp;
    private ArrayList<TLRPC.User> searchResultCommandsUsers;
    private ArrayList<TLRPC.BotInlineResult> searchResultBotContext;
    private HashMap<String, TLRPC.BotInlineResult> searchResultBotContextById;
    private MentionsAdapterDelegate delegate;
    private HashMap<Integer, TLRPC.BotInfo> botInfo;
    private int resultStartPosition;
    private int resultLength;
    private String lastText;
    private int lastPosition;
    private ArrayList<MessageObject> messages;
    private boolean needUsernames = true;
    private boolean needBotContext = true;
    private boolean isDarkTheme;
    private int botsCount;
    private boolean loadingBotRecent;
    private boolean botRecentLoaded;

    private String searchingContextUsername;
    private String searchingContextQuery;
    private String nextQueryOffset;
    private int contextUsernameReqid;
    private int contextQueryReqid;
    private boolean noUserName;
    private TLRPC.User foundContextBot;
    private boolean contextMedia;
    private Runnable contextQueryRunnable;

    public MentionsAdapter(Context context, boolean isDarkTheme, MentionsAdapterDelegate delegate) {
        mContext = context;
        this.delegate = delegate;
        this.isDarkTheme = isDarkTheme;
    }

    public void setChatInfo(TLRPC.ChatFull chatParticipants) {
        info = chatParticipants;
        if (lastText != null) {
            searchUsernameOrHashtag(lastText, lastPosition, messages);
        }
    }

    private void loadBotRecent() {
        if (loadingBotRecent || botRecentLoaded) {
            return;
        }
        loadingBotRecent = true;
        MessagesStorage.getInstance().getStorageQueue().postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLiteCursor cursor = MessagesStorage.getInstance().getDatabase().queryFinalized("SELECT id FROM bot_recent ORDER BY date DESC");
                    ArrayList<Integer> uids = null;
                    while (cursor.next()) {
                        if (uids == null) {
                            uids = new ArrayList<>();
                        }
                        uids.add(cursor.intValue(0));
                    }
                    cursor.dispose();
                    if (uids != null) {
                        final ArrayList<TLRPC.User> users = MessagesStorage.getInstance().getUsers(uids);
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                botRecent = users;
                                loadingBotRecent = false;
                                botRecentLoaded = true;
                                if (lastText != null) {
                                    searchUsernameOrHashtag(lastText, lastPosition, messages);
                                }
                            }
                        });
                    } else {
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                loadingBotRecent = false;
                                botRecentLoaded = true;
                            }
                        });
                    }
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void addRecentBot() {
        if (foundContextBot == null) {
            return;
        }
        if (botRecent == null) {
            botRecent = new ArrayList<>();
        } else {
            for (int a = 0; a < botRecent.size(); a++) {
                TLRPC.User user = botRecent.get(a);
                if (user.id == foundContextBot.id) {
                    botRecent.remove(a);
                    break;
                }
            }
        }
        botRecent.add(0, foundContextBot);
        final int uid = foundContextBot.id;
        MessagesStorage.getInstance().getStorageQueue().postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    SQLitePreparedStatement state = MessagesStorage.getInstance().getDatabase().executeFast("REPLACE INTO bot_recent VALUES(?, ?)");
                    state.requery();
                    state.bindInteger(1, uid);
                    state.bindInteger(2, (int) (System.currentTimeMillis() / 1000));
                    state.step();
                    state.dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void removeRecentBot(final TLRPC.User bot) {
        if (botRecent == null || bot == null) {
            return;
        }
        for (int a = 0; a < botRecent.size(); a++) {
            TLRPC.User user = botRecent.get(a);
            if (user.id == bot.id) {
                botRecent.remove(a);
                if (botRecent.isEmpty()) {
                    botRecent = null;
                }
                break;
            }
        }
        MessagesStorage.getInstance().getStorageQueue().postRunnable(new Runnable() {
            @Override
            public void run() {
                try {
                    MessagesStorage.getInstance().getDatabase().executeFast("DELETE FROM bot_recent WHERE id = " + bot.id).stepThis().dispose();
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        });
    }

    public void setNeedUsernames(boolean value) {
        needUsernames = value;
    }

    public void setNeedBotContext(boolean value) {
        needBotContext = value;
        if (needBotContext) {
            loadBotRecent();
        }
    }

    public void setBotInfo(HashMap<Integer, TLRPC.BotInfo> info) {
        botInfo = info;
    }

    public void setBotsCount(int count) {
        botsCount = count;
    }

    @Override
    public void clearRecentHashtags() {
        super.clearRecentHashtags();
        searchResultHashtags.clear();
        notifyDataSetChanged();
        if (delegate != null) {
            delegate.needChangePanelVisibility(false);
        }
    }

    @Override
    protected void setHashtags(ArrayList<HashtagObject> arrayList, HashMap<String, HashtagObject> hashMap) {
        super.setHashtags(arrayList, hashMap);
        if (lastText != null) {
            searchUsernameOrHashtag(lastText, lastPosition, messages);
        }
    }

    public int getContextBotId() {
        return foundContextBot != null ? foundContextBot.id : 0;
    }

    public String getContextBotName() {
        return foundContextBot != null ? foundContextBot.username : "";
    }

    private void searchForContextBot(final String username, final String query) {
        searchResultBotContext = null;
        searchResultBotContextById = null;
        notifyDataSetChanged();
        if (foundContextBot != null) {
            delegate.needChangePanelVisibility(false);
        }
        if (contextQueryRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(contextQueryRunnable);
            contextQueryRunnable = null;
        }
        if (username == null || username.length() == 0 || searchingContextUsername != null && !searchingContextUsername.equals(username)) {
            if (contextUsernameReqid != 0) {
                ConnectionsManager.getInstance().cancelRequest(contextUsernameReqid, true);
                contextUsernameReqid = 0;
            }
            if (contextQueryReqid != 0) {
                ConnectionsManager.getInstance().cancelRequest(contextQueryReqid, true);
                contextQueryReqid = 0;
            }
            foundContextBot = null;
            searchingContextUsername = null;
            searchingContextQuery = null;
            noUserName = false;
            if (delegate != null) {
                delegate.onContextSearch(false);
            }
            if (username == null || username.length() == 0) {
                return;
            }
        }
        if (query == null) {
            if (contextQueryReqid != 0) {
                ConnectionsManager.getInstance().cancelRequest(contextQueryReqid, true);
                contextQueryReqid = 0;
            }
            searchingContextQuery = null;
            if (delegate != null) {
                delegate.onContextSearch(false);
            }
            return;
        }
        if (delegate != null) {
            if (foundContextBot != null) {
                delegate.onContextSearch(true);
            } else if (username.equals("gif")) {
                searchingContextUsername = "gif";
                delegate.onContextSearch(false);
            }
        }
        searchingContextQuery = query;
        contextQueryRunnable = new Runnable() {
            @Override
            public void run() {
                if (contextQueryRunnable != this) {
                    return;
                }
                contextQueryRunnable = null;
                if (foundContextBot != null || noUserName) {
                    if (noUserName) {
                        return;
                    }
                    searchForContextBotResults(foundContextBot, query, "");
                } else {
                    TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
                    req.username = searchingContextUsername = username;
                    contextUsernameReqid = ConnectionsManager.getInstance().sendRequest(req, new RequestDelegate() {
                        @Override
                        public void run(final TLObject response, final TLRPC.TL_error error) {
                            AndroidUtilities.runOnUIThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (searchingContextUsername == null || !searchingContextUsername.equals(username)) {
                                        return;
                                    }
                                    contextUsernameReqid = 0;
                                    foundContextBot = null;
                                    if (error == null) {
                                        TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                                        if (!res.users.isEmpty()) {
                                            TLRPC.User user = res.users.get(0);
                                            if (user.bot && user.bot_inline_placeholder != null) {
                                                MessagesController.getInstance().putUser(user, false);
                                                MessagesStorage.getInstance().putUsersAndChats(res.users, null, true, true);
                                                foundContextBot = user;
                                            }
                                        }
                                    }
                                    if (foundContextBot == null) {
                                        noUserName = true;
                                    } else {
                                        if (delegate != null) {
                                            delegate.onContextSearch(true);
                                        }
                                        searchForContextBotResults(foundContextBot, searchingContextQuery, "");
                                    }
                                }
                            });

                        }
                    });
                }
            }
        };
        AndroidUtilities.runOnUIThread(contextQueryRunnable, 400);
    }

    public int getOrientation() {
        return searchResultBotContext != null && !searchResultBotContext.isEmpty() && contextMedia ? LinearLayoutManager.HORIZONTAL : LinearLayoutManager.VERTICAL;
    }

    public String getBotCaption() {
        if (foundContextBot != null) {
            return foundContextBot.bot_inline_placeholder;
        } else if (searchingContextUsername != null && searchingContextUsername.equals("gif")) {
            return "Search GIFs";
        }
        return null;
    }

    public void searchForContextBotForNextOffset() {
        if (contextQueryReqid != 0 || nextQueryOffset == null || nextQueryOffset.length() == 0 || foundContextBot == null || searchingContextQuery == null) {
            return;
        }
        searchForContextBotResults(foundContextBot, searchingContextQuery, nextQueryOffset);
    }

    private void searchForContextBotResults(TLRPC.User user, final String query, final String offset) {
        if (contextQueryReqid != 0) {
            ConnectionsManager.getInstance().cancelRequest(contextQueryReqid, true);
            contextQueryReqid = 0;
        }
        if (query == null || user == null) {
            searchingContextQuery = null;
            return;
        }
        TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
        req.bot = MessagesController.getInputUser(user);
        req.query = query;
        req.offset = offset;
        contextQueryReqid = ConnectionsManager.getInstance().sendRequest(req, new RequestDelegate() {
            @Override
            public void run(final TLObject response, final TLRPC.TL_error error) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (searchingContextQuery == null || !query.equals(searchingContextQuery)) {
                            return;
                        }
                        if (delegate != null) {
                            delegate.onContextSearch(false);
                        }
                        contextQueryReqid = 0;
                        if (error == null) {
                            TLRPC.TL_messages_botResults res = (TLRPC.TL_messages_botResults) response;
                            nextQueryOffset = res.next_offset;
                            if (searchResultBotContextById == null) {
                                searchResultBotContextById = new HashMap<>();
                            }
                            for (int a = 0; a < res.results.size(); a++) {
                                TLRPC.BotInlineResult result = res.results.get(a);
                                if (searchResultBotContextById.containsKey(result.id) || !(result.document instanceof TLRPC.TL_document) && !(result.photo instanceof TLRPC.TL_photo) && result.content_url == null && result.send_message instanceof TLRPC.TL_botInlineMessageMediaAuto) {
                                    res.results.remove(a);
                                    a--;
                                }
                                result.query_id = res.query_id;
                                searchResultBotContextById.put(result.id, result);
                            }
                            boolean added = false;
                            if (searchResultBotContext == null || offset.length() == 0) {
                                searchResultBotContext = res.results;
                            } else {
                                added = true;
                                searchResultBotContext.addAll(res.results);
                                if (res.results.isEmpty()) {
                                    nextQueryOffset = "";
                                }
                            }
                            contextMedia = res.gallery;
                            searchResultHashtags = null;
                            searchResultUsernames = null;
                            searchResultCommands = null;
                            searchResultCommandsHelp = null;
                            searchResultCommandsUsers = null;
                            if (added) {
                                notifyItemRangeInserted(searchResultBotContext.size() - res.results.size(), res.results.size());
                            } else {
                                notifyDataSetChanged();
                            }
                            delegate.needChangePanelVisibility(!searchResultBotContext.isEmpty());
                        }
                    }
                });
            }
        }, ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    public void searchUsernameOrHashtag(String text, int position, ArrayList<MessageObject> messageObjects) {
        if (text == null || text.length() == 0) {
            searchForContextBot(null, null);
            delegate.needChangePanelVisibility(false);
            lastText = null;
            return;
        }
        int searchPostion = position;
        if (text.length() > 0) {
            searchPostion--;
        }
        lastText = null;
        StringBuilder result = new StringBuilder();
        int foundType = -1;
        boolean hasIllegalUsernameCharacters = false;
        if (needBotContext && text.charAt(0) == '@') {
            int index = text.indexOf(' ');
            if (index > 0) {
                String username = text.substring(1, index);
                if (username.length() >= 1) {
                    for (int a = 1; a < username.length(); a++) {
                        char ch = username.charAt(a);
                        if (!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch == '_')) {
                            username = "";
                            break;
                        }
                    }
                } else {
                    username = "";
                }
                String query = text.substring(index + 1);
                if (username.length() > 0 && query.length() >= 0) {
                    searchForContextBot(username, query);
                } else {
                    searchForContextBot(username, null);
                }
            } else {
                searchForContextBot(null, null);
            }
        } else {
            searchForContextBot(null, null);
        }
        int dogPostion = -1;
        for (int a = searchPostion; a >= 0; a--) {
            if (a >= text.length()) {
                continue;
            }
            char ch = text.charAt(a);
            if (a == 0 || text.charAt(a - 1) == ' ' || text.charAt(a - 1) == '\n') {
                if (ch == '@') {
                    if (needUsernames || needBotContext && botRecent != null && a == 0) {
                        if (hasIllegalUsernameCharacters) {
                            delegate.needChangePanelVisibility(false);
                            return;
                        }
                        if (info == null && (botRecent == null || a != 0)) {
                            lastText = text;
                            lastPosition = position;
                            messages = messageObjects;
                            delegate.needChangePanelVisibility(false);
                            return;
                        }
                        if (loadingBotRecent) {
                            lastText = text;
                            lastPosition = position;
                            messages = messageObjects;
                        }
                        dogPostion = a;
                        foundType = 0;
                        resultStartPosition = a;
                        resultLength = result.length() + 1;
                        break;
                    } else if (loadingBotRecent) {
                        lastText = text;
                        lastPosition = position;
                        messages = messageObjects;
                    }
                } else if (ch == '#') {
                    if (!hashtagsLoadedFromDb) {
                        loadRecentHashtags();
                        lastText = text;
                        lastPosition = position;
                        messages = messageObjects;
                        delegate.needChangePanelVisibility(false);
                        return;
                    }
                    foundType = 1;
                    resultStartPosition = a;
                    resultLength = result.length() + 1;
                    result.insert(0, ch);
                    break;
                } else if (a == 0 && botInfo != null && ch == '/') {
                    foundType = 2;
                    resultStartPosition = a;
                    resultLength = result.length() + 1;
                    break;
                }
            }
            if (!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch == '_')) {
                hasIllegalUsernameCharacters = true;
            }
            result.insert(0, ch);
        }
        if (foundType == -1) {
            delegate.needChangePanelVisibility(false);
            return;
        }
        if (foundType == 0) {
            final ArrayList<Integer> users = new ArrayList<>();
            for (int a = 0; a < Math.min(100, messageObjects.size()); a++) {
                int from_id = messageObjects.get(a).messageOwner.from_id;
                if (!users.contains(from_id)) {
                    users.add(from_id);
                }
            }
            String usernameString = result.toString().toLowerCase();
            ArrayList<TLRPC.User> newResult = new ArrayList<>();
            final HashMap<Integer, TLRPC.User> newResultsHashMap = new HashMap<>();
            if (needBotContext && dogPostion == 0 && botRecent != null) {
                for (int a = 0; a < botRecent.size(); a++) {
                    TLRPC.User user = botRecent.get(a);
                    if (user.username != null && user.username.length() > 0 && (usernameString.length() > 0 && user.username.toLowerCase().startsWith(usernameString) || usernameString.length() == 0)) {
                        newResult.add(user);
                        newResultsHashMap.put(user.id, user);
                    }
                }
            }
            if (info != null && info.participants != null) {
                for (int a = 0; a < info.participants.participants.size(); a++) {
                    TLRPC.ChatParticipant chatParticipant = info.participants.participants.get(a);
                    TLRPC.User user = MessagesController.getInstance().getUser(chatParticipant.user_id);
                    if (user == null || UserObject.isUserSelf(user) || newResultsHashMap.containsKey(user.id)) {
                        continue;
                    }
                    if (user.username != null && user.username.length() > 0 && (usernameString.length() > 0 && user.username.toLowerCase().startsWith(usernameString) || usernameString.length() == 0)) {
                        newResult.add(user);
                    }
                }
            }
            searchResultHashtags = null;
            searchResultCommands = null;
            searchResultCommandsHelp = null;
            searchResultCommandsUsers = null;
            searchResultUsernames = newResult;
            Collections.sort(searchResultUsernames, new Comparator<TLRPC.User>() {
                @Override
                public int compare(TLRPC.User lhs, TLRPC.User rhs) {
                    if (newResultsHashMap.containsKey(lhs.id) && newResultsHashMap.containsKey(rhs.id)) {
                        return 0;
                    } else if (newResultsHashMap.containsKey(lhs.id)) {
                        return -1;
                    } else if (newResultsHashMap.containsKey(rhs.id)) {
                        return 1;
                    }
                    int lhsNum = users.indexOf(lhs.id);
                    int rhsNum = users.indexOf(rhs.id);
                    if (lhsNum != -1 && rhsNum != -1) {
                        return lhsNum < rhsNum ? -1 : (lhsNum == rhsNum ? 0 : 1);
                    } else if (lhsNum != -1 && rhsNum == -1) {
                        return -1;
                    } else if (lhsNum == -1 && rhsNum != -1) {
                        return 1;
                    }
                    return 0;
                }
            });
            notifyDataSetChanged();
            delegate.needChangePanelVisibility(!newResult.isEmpty());
        } else if (foundType == 1) {
            ArrayList<String> newResult = new ArrayList<>();
            String hashtagString = result.toString().toLowerCase();
            for (int a = 0; a < hashtags.size(); a++) {
                HashtagObject hashtagObject = hashtags.get(a);
                if (hashtagObject != null && hashtagObject.hashtag != null && hashtagObject.hashtag.startsWith(hashtagString)) {
                    newResult.add(hashtagObject.hashtag);
                }
            }
            searchResultHashtags = newResult;
            searchResultUsernames = null;
            searchResultCommands = null;
            searchResultCommandsHelp = null;
            searchResultCommandsUsers = null;
            notifyDataSetChanged();
            delegate.needChangePanelVisibility(!newResult.isEmpty());
        } else if (foundType == 2) {
            ArrayList<String> newResult = new ArrayList<>();
            ArrayList<String> newResultHelp = new ArrayList<>();
            ArrayList<TLRPC.User> newResultUsers = new ArrayList<>();
            String command = result.toString().toLowerCase();
            for (HashMap.Entry<Integer, TLRPC.BotInfo> entry : botInfo.entrySet()) {
                TLRPC.BotInfo botInfo = entry.getValue();
                for (int a = 0; a < botInfo.commands.size(); a++) {
                    TLRPC.TL_botCommand botCommand = botInfo.commands.get(a);
                    if (botCommand != null && botCommand.command != null && botCommand.command.startsWith(command)) {
                        newResult.add("/" + botCommand.command);
                        newResultHelp.add(botCommand.description);
                        newResultUsers.add(MessagesController.getInstance().getUser(botInfo.user_id));
                    }
                }
            }
            searchResultHashtags = null;
            searchResultUsernames = null;
            searchResultCommands = newResult;
            searchResultCommandsHelp = newResultHelp;
            searchResultCommandsUsers = newResultUsers;
            notifyDataSetChanged();
            delegate.needChangePanelVisibility(!newResult.isEmpty());
        }
    }

    public int getResultStartPosition() {
        return resultStartPosition;
    }

    public int getResultLength() {
        return resultLength;
    }

    @Override
    public int getItemCount() {
        if (searchResultBotContext != null) {
            return searchResultBotContext.size();
        } else if (searchResultUsernames != null) {
            return searchResultUsernames.size();
        } else if (searchResultHashtags != null) {
            return searchResultHashtags.size();
        } else if (searchResultCommands != null) {
            return searchResultCommands.size();
        }
        return 0;
    }

    @Override
    public int getItemViewType(int position) {
        if (searchResultBotContext != null) {
            return 1;
        } else {
            return 0;
        }
    }

    public Object getItem(int i) {
        if (searchResultBotContext != null) {
            if (i < 0 || i >= searchResultBotContext.size()) {
                return null;
            }
            return searchResultBotContext.get(i);
        } else if (searchResultUsernames != null) {
            if (i < 0 || i >= searchResultUsernames.size()) {
                return null;
            }
            return searchResultUsernames.get(i);
        } else if (searchResultHashtags != null) {
            if (i < 0 || i >= searchResultHashtags.size()) {
                return null;
            }
            return searchResultHashtags.get(i);
        } else if (searchResultCommands != null) {
            if (i < 0 || i >= searchResultCommands.size()) {
                return null;
            }
            if (searchResultCommandsUsers != null && (botsCount != 1 || info instanceof TLRPC.TL_channelFull)) {
                if (searchResultCommandsUsers.get(i) != null) {
                    return String.format("%s@%s", searchResultCommands.get(i), searchResultCommandsUsers.get(i) != null ? searchResultCommandsUsers.get(i).username : "");
                } else {
                    return String.format("%s", searchResultCommands.get(i));
                }
            }
            return searchResultCommands.get(i);
        }
        return null;
    }

    public boolean isLongClickEnabled() {
        return searchResultHashtags != null || searchResultCommands != null;
    }

    public boolean isBotCommands() {
        return searchResultCommands != null;
    }

    public boolean isBotContext() {
        return searchResultBotContext != null;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view;
        if (viewType == 1) {
            view = new ContextLinkCell(mContext);
            ((ContextLinkCell) view).setDelegate(new ContextLinkCell.ContextLinkCellDelegate() {
                @Override
                public void didPressedImage(ContextLinkCell cell) {
                    delegate.onContextClick(cell.getResult());
                }
            });
        } else {
            view = new MentionCell(mContext);
            ((MentionCell) view).setIsDarkTheme(isDarkTheme);
        }
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (searchResultBotContext != null) {
            ((ContextLinkCell) holder.itemView).setLink(searchResultBotContext.get(position), contextMedia, position != searchResultBotContext.size() - 1);
        } else {
            if (searchResultUsernames != null) {
                ((MentionCell) holder.itemView).setUser(searchResultUsernames.get(position));
            } else if (searchResultHashtags != null) {
                ((MentionCell) holder.itemView).setText(searchResultHashtags.get(position));
            } else if (searchResultCommands != null) {
                ((MentionCell) holder.itemView).setBotCommand(searchResultCommands.get(position), searchResultCommandsHelp.get(position), searchResultCommandsUsers != null ? searchResultCommandsUsers.get(position) : null);
            }
        }
    }
}

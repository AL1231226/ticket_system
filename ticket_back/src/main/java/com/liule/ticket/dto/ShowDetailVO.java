package com.liule.ticket.dto;

import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.ShowSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShowDetailVO {

    private Show show;

    /** 场次，每个场次带自己的票档列表 */
    private List<ShowSession> sessions;
}

# Connections

[← Guide](README.md){ .back }

Which territories are neighbours. Units move only between connected territories, so a missing
connection is an island.

??? task "Connect or part two territories"

    1. Click a territory on the map. Its connections show as red lines.
    2. Click a territory that is not connected to it: the two are now neighbours.
    3. Click a neighbour to part the two again.

    The right side counts the connections and shows the chosen territory's neighbours.

    ![The map in Connections: a territory chosen, its lines red, the other connections as lines](img/connections.png#only-light){ .wide }
    ![The map in Connections: a territory chosen, its lines red, the other connections as lines](img/connections-dark.png#only-dark){ .wide }

??? task "Find missing connections"

    ![The right side of Connections: the count, Find neighbours again…, Problems and Worth a look](img/connections-find.png#only-light){ .shot }
    ![The right side of Connections: the count, Find neighbours again…, Problems and Worth a look](img/connections-find-dark.png#only-dark){ .shot }

    1. Press **Find neighbours again…**. The editor compares the connections with the
       territories' outlines.
    2. Read the two lists: territories that touch without a connection, and connections that
       join territories that do not touch.
    3. Tick the ones to change, or press **Tick all** for the pairs that touch.
    4. Press **Change connections**.

    !!! tip
        Connections of territories that do not touch are often meant so: none of them is ticked
        at first.

??? task "Every control"

    - The map always shows the connections here, so it has no view button: every connection is a
      line between the middles of two territories. The chosen territory's lines are red.
    - **Find neighbours again…** compares the connections with the territories' outlines: which
      touch without a connection, and which connections join territories that do not touch. The
      result lists both under **Problems** and **Worth a look**, each with **Show**, and offers
      to add or remove them.
    - How to use it: click a territory, then click its neighbours. A click on a territory that
      is not connected connects it, a click on one that is parts them. The right side counts the
      connections and shows the chosen territory's neighbours.
    - Check (F6) also finds territories without a connection, and Home's guide has a step for
      it.

package com.kbagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
// 注意类名必须为 Main, 不要有任何 package xxx 信息
public class test {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        long n = in.nextLong();
        long k = in.nextLong();
        ArrayList<Long> ls = new ArrayList<>();
        HashMap<Long,Boolean> mp = new HashMap<>();
        long ans = 0;
        for (int i = 0; i < n; i++) {
            Long x = in.nextLong();
            ls.add(x);
        }
        for (int i = 0; i < k; i++) {
            Long x = in.nextLong();
            mp.put(x, true);
        }
        for (int i = 1; i <= n; i++) {
            if(!mp.containsKey(i)) {
                ans += ls.get(i-1);
            }
        }
        System.out.println(ans);
    }
}
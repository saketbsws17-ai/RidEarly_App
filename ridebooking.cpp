#include <iostream>
#include <vector>
#include <algorithm>
using namespace std;

struct Edge {
    int u, v;
    double w;
};

struct Driver {
    int node;
    double rating, pricePerKm;
    int type;
    bool available = true;
};

struct Rider {
    int node;
};

vector<double> bellmanFord(int V, vector<Edge>& edges, int src) {
    vector<double> dist(V, 1e9);
    dist[src] = 0;

    for (int i = 0; i < V - 1; i++) {
        for (auto e : edges) {
            if (dist[e.u] + e.w < dist[e.v]) {
                dist[e.v] = dist[e.u] + e.w;
            }
        }
    }
    return dist;
}

string vehicleName(int t) {
    string v[] = {"Bike", "Mini", "Sedan", "SUV"};
    return v[t];
}

int main() {
    int V, E;
    cout << "Enter nodes and edges: ";
    cin >> V >> E;

    vector<Edge> edges(E);

    cout << "Enter edges (u v weight):\n";
    for (int i = 0; i < E; i++) {
        cin >> edges[i].u >> edges[i].v >> edges[i].w;
    }

    int d, r;
    cout << "Enter drivers and riders: ";
    cin >> d >> r;

    vector<Driver> drivers(d);
    vector<Rider> riders(r);

    cout << "\nEnter Driver (node rating price type):\n";
    for (int i = 0; i < d; i++) {
        cin >> drivers[i].node >> drivers[i].rating
            >> drivers[i].pricePerKm >> drivers[i].type;
    }

    cout << "\nEnter Rider nodes:\n";
    for (int i = 0; i < r; i++) {
        cin >> riders[i].node;
    }

    for (int i = 0; i < r; i++) {

        cout << "\n--- Rider " << i << " ---\n";

        int pref;
        cout << "Select Type (0 Bike,1 Mini,2 Sedan,3 SUV): ";
        cin >> pref;

        vector<double> dist = bellmanFord(V, edges, riders[i].node);

        struct Option {
            int id;
            double dist, fare, rating;
        };

        vector<Option> opt;

        for (int j = 0; j < d; j++) {
            if (!drivers[j].available || drivers[j].type != pref)
                continue;

            if (dist[drivers[j].node] < 1e9) {
                opt.push_back({
                    j,
                    dist[drivers[j].node],
                    dist[drivers[j].node] * drivers[j].pricePerKm,
                    drivers[j].rating
                });
            }
        }

        if (opt.empty()) {
            cout << "No drivers available!\n";
            continue;
        }

        sort(opt.begin(), opt.end(),
             [](auto a, auto b) { return a.fare < b.fare; });

        int limit = min(3, (int)opt.size());

        cout << "\nTop Drivers:\n";
        for (int k = 0; k < limit; k++) {
            int id = opt[k].id;
            cout << k << ". Driver " << id
                 << " (" << vehicleName(drivers[id].type) << ")"
                 << " | Dist: " << opt[k].dist
                 << " | Fare: Rs " << opt[k].fare
                 << " | Rating: " << opt[k].rating << endl;
        }

        int choice;
        cout << "Choose (0-" << limit - 1 << "): ";
        cin >> choice;

        if (choice >= 0 && choice < limit) {
            int sel = opt[choice].id;

            drivers[sel].available = false;
            drivers[sel].node = riders[i].node;

            cout << "Driver " << sel << " assigned!\n";
        } else {
            cout << "Invalid choice!\n";
        }
    }

    cout << "\nAll rides completed.\n";
}
